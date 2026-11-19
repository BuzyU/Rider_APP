package com.ridervoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VoxDetectorTest {

    private val rnd = Random(42)

    /** White-noise frame scaled to the requested (pre-filter) RMS. */
    private fun noiseFrame(rms: Float): ShortArray {
        val f = ShortArray(VoxDetector.FRAME)
        for (i in f.indices) f[i] = (rnd.nextGaussian() * rms).toInt().coerceIn(-32000, 32000).toShort()
        return f
    }

    private class Sim(val d: VoxDetector) {
        var now = 1_000L
        var open = false
        var openedAt = -1L
        var closedAt = -1L
        var opens = 0

        fun step(level: Float, gen: (Float) -> ShortArray) {
            val frame = gen(level)
            val r = d.rms(frame, frame.size)
            d.updateFloor(r)
            when (d.evaluate(r, now, open)) {
                VoxDetector.Decision.OPEN -> { open = true; openedAt = now; opens++ }
                VoxDetector.Decision.CLOSE -> { open = false; closedAt = now }
                VoxDetector.Decision.NONE -> {}
            }
            now += 20
        }
    }

    @Test
    fun steadyNoiseConvergesAndNeverOpens() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) } // 10 s
        assertFalse(sim.open)
        assertEquals(0, sim.opens)
        assertEquals(400f, sim.d.floor, 400f * 0.2f)
    }

    @Test
    fun burstOpensQuicklyAndClosesAfterHold() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) }
        val burstStart = sim.now
        repeat(10) { sim.step(400f * 6f, ::noiseFrame) } // 200 ms burst
        assertTrue(sim.open)
        assertTrue("open latency ${sim.openedAt - burstStart}", sim.openedAt - burstStart <= 80)
        val burstEnd = sim.now
        repeat(80) { sim.step(400f, ::noiseFrame) }
        assertFalse(sim.open)
        val closeDelay = sim.closedAt - burstEnd
        assertTrue("close delay $closeDelay", closeDelay in 700..1100)
    }

    @Test
    fun noiseStepUpDoesNotLatchOpen() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) }
        repeat(1500) { sim.step(2000f, ::noiseFrame) } // 30 s at 5x
        // may have opened once, but must be closed again after the floor adapts
        assertTrue(sim.opens <= 1)
        assertFalse(sim.open)
    }

    @Test
    fun continuousSpeechDoesNotCutAfter2Seconds() {
        val sim = Sim(VoxDetector())
        // Settle floor on background noise (10 s)
        repeat(500) { sim.step(400f, ::noiseFrame) }

        // Trigger opening burst
        repeat(5) { sim.step(400f * 6f, ::noiseFrame) }
        assertTrue("VOX must open on speech", sim.open)
        assertEquals(1, sim.opens)

        // 30 seconds of conversational speech:
        // ~50 words, each 300 ms speech followed by 250 ms breath/word pause (< 900 ms HOLD_MS)
        // VOX-CUT-1 regression test: must NEVER cut at 1.8-2.0s!
        repeat(55) {
            // Word: 15 frames = 300 ms
            repeat(15) { sim.step(3000f, ::noiseFrame) }
            assertTrue("VOX must stay open during speech words", sim.open)

            // Inter-word pause: 12 frames = 240 ms (shorter than 900 ms hold)
            repeat(12) { sim.step(400f, ::noiseFrame) }
            assertTrue("VOX must stay open during inter-word pause within hold window", sim.open)
        }
        assertEquals("VOX must stay open without chopping/reopening across 30s conversation", 1, sim.opens)

        // Final spoken word before conversation ends
        repeat(15) { sim.step(3000f, ::noiseFrame) }
        val speechEnd = sim.now

        // Rider stops speaking: silence for 60 frames (1.2 s)
        repeat(60) { sim.step(400f, ::noiseFrame) }
        assertFalse("VOX must close after speech ends and hold expires", sim.open)
        val closeDelay = sim.closedAt - speechEnd
        assertTrue("Close delay should match hold time (~900 ms), was $closeDelay", closeDelay in 700..1100)
    }

    @Test
    fun clippedAudio_handledGracefullyWithoutCrashOrNan() {
        val sim = Sim(VoxDetector())
        repeat(50) { sim.step(400f, ::noiseFrame) }
        // Clipped loud frame at ±32767
        val clippedFrame: (Float) -> ShortArray = {
            ShortArray(VoxDetector.FRAME) { i -> if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        }
        sim.step(32767f, clippedFrame)
        assertTrue(sim.d.floor > 0f)
        assertFalse(sim.d.floor.isNaN())
        assertFalse(sim.d.openThreshold.isNaN())
        assertFalse(sim.d.floor.isInfinite())
    }

    @Test
    fun voxDetector_48kHzAnd16kHz_ConsistentHoldAndFrameDuration() {
        for (rate in listOf(16000, 48000)) {
            val frameSize = rate / 50 // 20 ms frame: 320 samples @ 16 kHz, 960 samples @ 48 kHz
            val detector = VoxDetector(
                cfg = VoxConfig(attackMs = 20, holdMs = 100),
                initialSampleRate = rate
            )
            val speechFrame = ShortArray(frameSize) { i ->
                (8000.0 * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / rate)).toInt().toShort()
            }
            val silenceFrame = ShortArray(frameSize)

            var nowMs = 1_000L
            val openDecision = detector.onFrame(speechFrame, rate, nowMs, isOpen = false)
            assertEquals("Expected immediate open on 20 ms speech frame at ${rate}Hz", VoxDetector.Decision.OPEN, openDecision)

            // Hold for 4 silent frames (20, 40, 60, 80 ms < 100 ms hold)
            repeat(4) { step ->
                nowMs += 20L
                val holdDecision = detector.onFrame(silenceFrame, rate, nowMs, isOpen = true)
                assertEquals("Expected hold during silent frame ${step + 1} at ${rate}Hz", VoxDetector.Decision.NONE, holdDecision)
            }

            // Close on the 5th silent frame (100 ms == holdMs)
            nowMs += 20L
            val closeDecision = detector.onFrame(silenceFrame, rate, nowMs, isOpen = true)
            assertEquals("Expected close at 100 ms hold expiry at ${rate}Hz", VoxDetector.Decision.CLOSE, closeDecision)
        }
    }

    @Test
    fun tenMillisecond48kHzCallbacksMatchTwentyMillisecond16kHzNoiseFloorWindow() {
        val cfg = VoxConfig(floorWindowMs = 5000, analysisMs = 20, initialFloor = 400f)
        val tracker16k20ms = NoiseFloorTracker(cfg)
        val tracker48k10ms = NoiseFloorTracker(cfg)

        // Feed 2.5 seconds of 400 RMS ambient noise, followed by 2.5 seconds of 1200 RMS
        repeat(125) { tracker16k20ms.update(400f, frameDurationMs = 20) }
        repeat(125) { tracker16k20ms.update(1200f, frameDurationMs = 20) }

        repeat(250) { tracker48k10ms.update(400f, frameDurationMs = 10) }
        repeat(250) { tracker48k10ms.update(1200f, frameDurationMs = 10) }

        // Both must span the same 5000 ms window (not 2500 ms for 10ms) and arrive at the same floor (~400f)
        assertEquals(tracker16k20ms.floor, tracker48k10ms.floor, 25f)
        assertTrue("10ms floor must remain anchored near 400f over 5s window, was ${tracker48k10ms.floor}", tracker48k10ms.floor < 550f)
    }

    @Test
    fun continuousModulatedSpeechOver15SecondsDoesNotSelfPoisonFloorOrCutOffAt8Seconds() {
        com.ridervoice.utils.RvLog.resetAudioCounters()
        val detector = VoxDetector(initialSampleRate = 16000)
        var now = 1_000L

        // Warm up baseline floor at ~350 RMS for 4 seconds
        repeat(200) {
            val d = detector.onFrame(noiseFrame(350f), 16000, now, isOpen = false)
            assertEquals(VoxDetector.Decision.NONE, d)
            now += 20L
        }
        val baselineFloor = detector.floor

        // Open gate on speech onset
        var open = false
        repeat(5) {
            val d = detector.onFrame(noiseFrame(3000f), 16000, now, isOpen = open)
            if (d == VoxDetector.Decision.OPEN) open = true
            now += 20L
        }
        assertTrue("Gate must open cleanly on speech onset", open)

        // Simulate 15 seconds (750 frames) of continuous uninterrupted speech varying dynamically between
        // soft phonemes (~1400f > closeThreshold ~630f) and louder vowels (~3600f) WITHOUT dropping below closeThreshold.
        // Previously, NoiseFloorTracker self-poisoned floor upward AND maxOpenWithoutSpeechMs=8000 force-closed at 8.0s!
        for (idx in 0 until 750) {
            val syllableLevel = if ((idx / 8) % 2 == 0) 3600f else 1400f
            val decision = detector.onFrame(noiseFrame(syllableLevel), 16000, now, isOpen = true)
            assertFalse(
                "Continuous speech must NEVER cut off mid-sentence (cut off at elapsed=${idx * 20}ms, floor=${detector.floor})",
                decision == VoxDetector.Decision.CLOSE
            )
            now += 20L
        }
        assertTrue("Noise floor must not self-poison above 2x baseline during open speech (baseline=$baselineFloor, now=${detector.floor})", detector.floor <= baselineFloor * 1.5f)
    }

    @Test
    fun longestSilenceDuringTalkspurtIsTrackedAndRecordedToRvLog() {
        com.ridervoice.utils.RvLog.resetAudioCounters()
        val detector = VoxDetector(cfg = VoxConfig(attackMs = 20, holdMs = 900), initialSampleRate = 16000)
        detector.setFloor(300f)
        var now = 1_000L

        // Open on speech
        val openDecision = detector.evaluate(rms = 2500f, now = now, isOpen = false, frameDurationMs = 20L)
        assertEquals(VoxDetector.Decision.OPEN, openDecision)
        assertEquals(1L, com.ridervoice.utils.RvLog.gateOpenCount.get())

        // Pause for 400ms (< 900ms hold), then resume speech for 200ms
        repeat(20) {
            now += 20L
            assertEquals(VoxDetector.Decision.NONE, detector.evaluate(100f, now, isOpen = true, frameDurationMs = 20L))
        }
        repeat(10) {
            now += 20L
            assertEquals(VoxDetector.Decision.NONE, detector.evaluate(2500f, now, isOpen = true, frameDurationMs = 20L))
        }
        assertTrue("Expected intermediate silence >= 380ms tracked", detector.getCurrentLongestSilenceMs() >= 380L)

        // Now stop speaking for 920ms until hold expires
        var closed = false
        for (step in 1..60) {
            now += 20L
            if (detector.evaluate(100f, now, isOpen = true, frameDurationMs = 20L) == VoxDetector.Decision.CLOSE) {
                closed = true
                break
            }
        }
        assertTrue(closed)
        assertEquals(1L, com.ridervoice.utils.RvLog.gateCloseCount.get())
        assertTrue(
            "RvLog.lastLongestSilenceInTalkspurtMs must capture hold/silence duration (>= 900ms), was ${com.ridervoice.utils.RvLog.lastLongestSilenceInTalkspurtMs.get()}",
            com.ridervoice.utils.RvLog.lastLongestSilenceInTalkspurtMs.get() >= 900L
        )
    }
}


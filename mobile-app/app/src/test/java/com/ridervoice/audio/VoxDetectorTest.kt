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
}

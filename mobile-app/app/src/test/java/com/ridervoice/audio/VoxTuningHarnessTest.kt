package com.ridervoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Synthetic-PCM Audio and VOX Tuning Test Harness (Finding V3-2)
 *
 * Measures:
 * 1. False opens per minute under steady wind and engine noise.
 * 2. Speech onset latency (attack duration) in milliseconds.
 * 3. Stuck-open / hold duration after speech stops.
 * 4. High-pass filter attenuation of low-frequency engine rumble (<100 Hz).
 * 5. Robustness against clipped full-scale PCM signals.
 */
class VoxTuningHarnessTest {

    private val SAMPLE_RATE = 16000
    private val FRAME_SAMPLES = 320 // 20 ms at 16 kHz

    /**
     * Generate synthetic PCM 16-bit mono frame with specified frequencies and amplitude.
     */
    private fun generateSineFrame(frequencyHz: Double, amplitude: Short, sampleOffset: Int): ShortArray {
        val frame = ShortArray(FRAME_SAMPLES)
        for (i in 0 until FRAME_SAMPLES) {
            val t = (sampleOffset + i).toDouble() / SAMPLE_RATE
            frame[i] = (amplitude * sin(2.0 * Math.PI * frequencyHz * t)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return frame
    }

    /**
     * Generate synthetic pseudo-random wind/white noise frame with a given peak amplitude.
     */
    private fun generateNoiseFrame(amplitude: Short, seed: Int): ShortArray {
        val frame = ShortArray(FRAME_SAMPLES)
        var s = seed
        for (i in 0 until FRAME_SAMPLES) {
            // Linear congruential generator for deterministic synthetic noise
            s = (s * 1103515245 + 12345) and 0x7FFFFFFF
            val norm = (s.toFloat() / 0x7FFFFFFF.toFloat()) * 2f - 1f
            frame[i] = (norm * amplitude).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return frame
    }

    @Test
    fun highPassFilterAttenuatesLowFrequencyEngineRumble() {
        val filter = BiquadHighPass(cutoffHz = 130f)
        filter.updateSampleRate(SAMPLE_RATE)

        // 60 Hz engine rumble
        var rumbleEnergy = 0.0
        for (f in 0 until 50) {
            val frame = generateSineFrame(frequencyHz = 60.0, amplitude = 2000, sampleOffset = f * FRAME_SAMPLES)
            val rms = filter.process(frame, frame.size)
            if (f > 10) rumbleEnergy += rms // steady-state
        }

        // 500 Hz human voice fundamental
        filter.reset()
        var voiceEnergy = 0.0
        for (f in 0 until 50) {
            val frame = generateSineFrame(frequencyHz = 500.0, amplitude = 2000, sampleOffset = f * FRAME_SAMPLES)
            val rms = filter.process(frame, frame.size)
            if (f > 10) voiceEnergy += rms
        }

        // 60 Hz rumble should be heavily attenuated compared to 500 Hz speech
        val attenuationRatio = voiceEnergy / rumbleEnergy
        assertTrue("High-pass filter must attenuate 60Hz rumble by at least 3x (approx 10dB) compared to 500Hz: ratio=$attenuationRatio", attenuationRatio > 3.0)
    }

    @Test
    fun steadyWindNoiseProducesZeroFalseOpensPerMinute() {
        val detector = VoxDetector()
        var falseOpens = 0
        var now = 1000L

        // Warm up noise floor with 5 seconds of steady wind noise (RMS ~ 400)
        for (f in 0 until 250) {
            val frame = generateNoiseFrame(amplitude = 600, seed = f * 31)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            detector.updateFloor(rms)
            now += 20L
        }

        // Simulate 60 seconds (3000 frames) of steady highway wind noise
        for (f in 250 until 3250) {
            val frame = generateNoiseFrame(amplitude = 650, seed = f * 37)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            detector.updateFloor(rms)
            val decision = detector.evaluate(rms, now, isOpen = false, frameDurationMs = 20L)
            if (decision == VoxDetector.Decision.OPEN) {
                falseOpens++
            }
            now += 20L
        }

        assertEquals("Steady wind noise must produce 0 false opens per minute", 0, falseOpens)
    }

    @Test
    fun speechOnsetTriggersOpenWithin60Milliseconds() {
        val detector = VoxDetector()
        var now = 10000L

        // Baseline silence/low background noise floor
        for (f in 0 until 100) {
            val frame = generateNoiseFrame(amplitude = 200, seed = f * 17)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            detector.updateFloor(rms)
            now += 20L
        }

        // Rider starts speaking loudly (harmonic speech at 300Hz, amplitude 5000)
        var opened = false
        var speechStartMs = now
        var openLatencyMs = 0L

        for (f in 0 until 10) { // 200 ms of speech
            val frame = generateSineFrame(frequencyHz = 300.0, amplitude = 5000, sampleOffset = f * FRAME_SAMPLES)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            val decision = detector.evaluate(rms, now, isOpen = false, frameDurationMs = 20L)
            if (decision == VoxDetector.Decision.OPEN && !opened) {
                opened = true
                openLatencyMs = now - speechStartMs
                break
            }
            now += 20L
        }

        assertTrue("Speech onset must trigger VOX OPEN decision", opened)
        // Attack is configured to 60 ms (3 frames of 20ms)
        assertTrue("Onset latency ($openLatencyMs ms) must be <= 60 ms to avoid clipping first syllables", openLatencyMs <= 60L)
    }

    @Test
    fun holdTimeMaintainsMicOpenForConfiguredHoldDuration() {
        val detector = VoxDetector()
        var now = 20000L

        // Warm floor
        for (f in 0 until 100) {
            val frame = generateNoiseFrame(amplitude = 200, seed = f)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            detector.updateFloor(rms)
            now += 20L
        }

        // Speak to open mic
        for (f in 0 until 5) {
            val frame = generateSineFrame(350.0, 5000, f * FRAME_SAMPLES)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            detector.evaluate(rms, now, isOpen = false, frameDurationMs = 20L)
            now += 20L
        }

        // Speech stops; silence starts at t0
        val speechEndMs = now
        var closedAtMs = 0L

        // Feed silence/background noise until it closes
        for (f in 0 until 100) { // 2000 ms
            val frame = generateNoiseFrame(amplitude = 150, seed = f * 3)
            val rms = detector.rms(frame, frame.size, SAMPLE_RATE)
            val decision = detector.evaluate(rms, now, isOpen = true, frameDurationMs = 20L)
            if (decision == VoxDetector.Decision.CLOSE) {
                closedAtMs = now
                break
            }
            now += 20L
        }

        assertTrue("Mic must eventually close after speech stops", closedAtMs > speechEndMs)
        val holdDuration = closedAtMs - speechEndMs
        // Configured hold time is 900 ms (+- 40ms frame quantum)
        assertTrue("Hold duration ($holdDuration ms) must be between 860ms and 940ms", holdDuration in 860L..940L)
    }

    @Test
    fun clippedSignalDoesNotCauseExceptionOrInfiniteFloor() {
        val detector = VoxDetector()
        val clippedFrame = ShortArray(FRAME_SAMPLES) { Short.MAX_VALUE }

        val rms = detector.rms(clippedFrame, clippedFrame.size, SAMPLE_RATE)
        detector.updateFloor(rms)

        // Floor must be clamped at cfg.maxFloor (4000)
        assertTrue("Noise floor must clamp at maxFloor (4000f), actual: ${detector.floor}", detector.floor <= 4000f)
    }
}

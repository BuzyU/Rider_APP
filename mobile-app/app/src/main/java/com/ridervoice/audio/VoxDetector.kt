package com.ridervoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM voice-activity detector core (no Android dependencies, unit-testable).
 *
 * Operates on 20 ms frames (320 samples @ 16 kHz). The high-pass filter is applied ONLY to
 * the detector's own copy of the samples; transmitted audio is never touched.
 */
class VoxDetector(sampleRate: Int = 16000) {

    enum class Decision { NONE, OPEN, CLOSE }

    companion object {
        const val FRAME = 320
        const val CLOSE_RATIO = 1.8f
        const val ATTACK_FRAMES = 3
        const val HOLD_MS = 900L
        const val FLOOR_MIN = 120f
        const val FLOOR_MAX = 4000f
        const val DEFAULT_OPEN_RATIO = 3.0f
    }

    @Volatile var openRatio = DEFAULT_OPEN_RATIO
    @Volatile var speedExtra = 1.0f

    var floor = 800f
        private set

    private var aboveFrames = 0
    private var lastSpeechTime = 0L

    // Biquad high-pass, fc = 300 Hz, Q = 0.707
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        val w0 = 2.0 * PI * 300.0 / sampleRate
        val alpha = sin(w0) / (2.0 * 0.707)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (((1.0 + c) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + c)) / a0).toFloat()
        b2 = (((1.0 + c) / 2.0) / a0).toFloat()
        a1 = ((-2.0 * c) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    val openThreshold: Float get() = floor * openRatio * speedExtra
    val closeThreshold: Float get() = floor * CLOSE_RATIO * speedExtra

    /** Speed factor: 1.0 / 1.19 (>80 km/h) / 1.41 (>120 km/h). */
    fun setSpeedKmh(kmh: Float) {
        speedExtra = when {
            kmh > 120f -> 1.41f
            kmh > 80f -> 1.19f
            else -> 1.0f
        }
    }

    /** RMS of the high-passed frame (updates filter state). */
    fun rms(frame: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val x = frame[i].toFloat()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            sum += y.toDouble() * y.toDouble()
        }
        return sqrt(sum / count).toFloat()
    }

    /** Runs on EVERY frame when PTT is not held (also while the mic is open -> no latch). */
    fun updateFloor(rms: Float) {
        var f = floor
        f += when {
            rms < f -> 0.15f * (rms - f)
            rms < f * CLOSE_RATIO -> 0.01f * (rms - f)
            else -> 0.001f * (rms - f)
        }
        floor = f.coerceIn(FLOOR_MIN, FLOOR_MAX)
    }

    fun setFloor(value: Float) {
        floor = value.coerceIn(FLOOR_MIN, FLOOR_MAX)
    }

    fun evaluate(rms: Float, now: Long, isOpen: Boolean): Decision {
        if (!isOpen) {
            aboveFrames = if (rms >= openThreshold) aboveFrames + 1 else 0
            if (aboveFrames >= ATTACK_FRAMES) {
                lastSpeechTime = now
                aboveFrames = 0
                return Decision.OPEN
            }
        } else {
            if (rms >= closeThreshold) {
                lastSpeechTime = now
            } else if (now - lastSpeechTime >= HOLD_MS) {
                return Decision.CLOSE
            }
        }
        return Decision.NONE
    }

    fun onPttRelease(now: Long) {
        lastSpeechTime = now
        aboveFrames = 0
    }

    fun resetAttack() {
        aboveFrames = 0
    }
}

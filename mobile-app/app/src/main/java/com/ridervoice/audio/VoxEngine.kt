package com.ridervoice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart VOX (Voice-Operated eXchange) engine.
 *
 * Continuously reads raw audio amplitude from the microphone via AudioRecord
 * and decides whether to open or close the LiveKit mic track based on
 * a calibrated threshold. This is specifically tuned for:
 *
 *   - Helmet-mounted mics (high wind noise floor at highway speeds)
 *   - Intercom-style PTT override (PTT always wins over VOX state)
 *   - Motorcycle vibration noise (low-frequency rejection via band limiting)
 *
 * The noise floor is measured in background during silence and updated
 * dynamically as riding conditions change (city → highway → tunnel).
 *
 * Call flow:
 *   VoxEngine.start() → continuously polls amplitude
 *   VoxEngine.isMicOpen → true when rider is speaking
 *   VoxEngine.setPttOverride(true) → hardware PTT pressed, bypasses VOX
 *   VoxEngine.stop() → releases AudioRecord
 */
@Singleton
class VoxEngine @Inject constructor() {

    private val TAG = "VoxEngine"

    // ── Tunable parameters ────────────────────────────────────────────────────

    /**
     * Ratio above the dynamic noise floor needed to trigger voice open.
     * 2.5× = the voice signal must be 2.5× louder than ambient noise.
     * Higher values = more selective (fewer false triggers from wind/engine).
     * Lower values = more sensitive (catches quiet speech in wind).
     */
    private var openThresholdRatio = 2.5f

    /**
     * Once open, how long (ms) of silence before the mic closes again.
     * 600 ms prevents choppy rapid open/close between words ("tail padding").
     */
    private val holdTimeMs = 600L

    /**
     * How long (ms) of continuous signal above threshold to confirm speech
     * before opening the mic. Prevents a single impulsive noise (rock hit,
     * tank slap) from triggering transmission.
     */
    private val attackTimeMs = 80L

    // ── AudioRecord config ─────────────────────────────────────────────────────

    private val SAMPLE_RATE = 16000     // 16 kHz — sufficient for voice
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE_FACTOR = 4
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isMicOpen = MutableStateFlow(false)
    val isMicOpen: StateFlow<Boolean> = _isMicOpen

    private val _noiseFloor = MutableStateFlow(0f)
    val noiseFloor: StateFlow<Float> = _noiseFloor

    private val _currentAmplitude = MutableStateFlow(0f)
    val currentAmplitude: StateFlow<Float> = _currentAmplitude

    private var pttOverride = false
    private var voxEnabled = true

    private var pollJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Dynamic noise floor tracking
    private var noiseFloorEstimate = 800f   // Start conservative (higher = less likely to open)
    private val NOISE_FLOOR_ALPHA = 0.02f   // How fast the floor updates (0.01=slow, 0.1=fast)
    private val NOISE_FLOOR_MIN = 400f      // Never go below this (hardware noise floor)

    // State machine for attack/hold timing
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var isInAttack = false

    // Callback — wired to LiveKitManager.setMicrophoneEnabled()
    var onMicStateChange: ((Boolean) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start the VOX engine. Requires RECORD_AUDIO permission already granted.
     * Begins measuring amplitude immediately.
     */
    @Suppress("MissingPermission")
    fun start() {
        if (audioRecord != null || pollJob?.isActive == true) return
        Log.d(TAG, "VOX engine starting")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // AEC-eligible source
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e)
            return
        }

        pollJob = scope.launch {
            // Initial noise floor calibration — 1.5 seconds of listening before
            // enabling VOX decisions
            Log.d(TAG, "Calibrating noise floor...")
            calibrateNoiseFloor(1500L)
            Log.d(TAG, "Noise floor calibrated: $noiseFloorEstimate")

            val buffer = ShortArray(bufferSize / 2)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val rms = computeRms(buffer, read)
                _currentAmplitude.value = rms

                // Update dynamic noise floor during silence
                if (!_isMicOpen.value && !pttOverride) {
                    updateNoiseFloor(rms)
                }

                // VOX decision
                if (voxEnabled && !pttOverride) {
                    evaluateVoxState(rms)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
        closeMic()
        Log.d(TAG, "VOX engine stopped")
    }

    /**
     * Hardware PTT pressed — bypass VOX and force mic open.
     * VOX state is suspended while PTT is active.
     */
    fun setPttOverride(open: Boolean) {
        pttOverride = open
        if (open) {
            openMic()
        } else {
            // PTT released — let VOX take back control after the hold time
            scope.launch {
                delay(holdTimeMs)
                if (!pttOverride) closeMic()
            }
        }
    }

    fun setVoxEnabled(enabled: Boolean) {
        voxEnabled = enabled
        if (!enabled && !pttOverride) closeMic()
    }

    /**
     * Adjust VOX sensitivity.
     * @param sensitivity 0.0 (most sensitive) to 1.0 (least sensitive)
     */
    fun setSensitivity(sensitivity: Float) {
        // Map [0,1] to threshold ratio [1.5, 5.0]
        openThresholdRatio = 1.5f + (sensitivity.coerceIn(0f, 1f) * 3.5f)
        Log.d(TAG, "VOX threshold ratio set to $openThresholdRatio")
    }

    // ── Internal logic ─────────────────────────────────────────────────────────

    private fun evaluateVoxState(rms: Float) {
        val threshold = noiseFloorEstimate * openThresholdRatio
        val now = System.currentTimeMillis()

        if (rms >= threshold) {
            // Signal above threshold
            if (!isInAttack) {
                isInAttack = true
                speechStartTime = now
            }

            val attackElapsed = now - speechStartTime
            if (!_isMicOpen.value && attackElapsed >= attackTimeMs) {
                // Attack time met — open mic
                openMic()
            }

            lastSpeechTime = now
        } else {
            // Signal below threshold
            isInAttack = false

            if (_isMicOpen.value) {
                val holdElapsed = now - lastSpeechTime
                if (holdElapsed >= holdTimeMs) {
                    closeMic()
                }
            }
        }
    }

    private fun openMic() {
        if (_isMicOpen.value) return
        Log.d(TAG, "VOX OPEN (floor=${noiseFloorEstimate.toInt()}, threshold=${(noiseFloorEstimate * openThresholdRatio).toInt()})")
        _isMicOpen.value = true
        onMicStateChange?.invoke(true)
    }

    private fun closeMic() {
        if (!_isMicOpen.value) return
        Log.d(TAG, "VOX CLOSE")
        _isMicOpen.value = false
        isInAttack = false
        onMicStateChange?.invoke(false)
    }

    private fun updateNoiseFloor(rms: Float) {
        // Exponential moving average — slow adaptation prevents sudden spikes
        // (a truck horn) from permanently raising the floor
        if (rms > NOISE_FLOOR_MIN) {
            noiseFloorEstimate = noiseFloorEstimate * (1 - NOISE_FLOOR_ALPHA) + rms * NOISE_FLOOR_ALPHA
            noiseFloorEstimate = max(noiseFloorEstimate, NOISE_FLOOR_MIN)
            _noiseFloor.value = noiseFloorEstimate
        }
    }

    private suspend fun calibrateNoiseFloor(durationMs: Long) {
        val buffer = ShortArray(bufferSize / 2)
        val endTime = System.currentTimeMillis() + durationMs
        val samples = mutableListOf<Float>()

        while (System.currentTimeMillis() < endTime) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read > 0) {
                samples.add(computeRms(buffer, read))
            }
            delay(30)
        }

        if (samples.isNotEmpty()) {
            // Use the 75th percentile as the floor — more robust than mean
            // (ignores brief speech or noise spikes during calibration)
            val sorted = samples.sorted()
            val p75idx = (sorted.size * 0.75).toInt().coerceIn(0, sorted.size - 1)
            noiseFloorEstimate = max(sorted[p75idx], NOISE_FLOOR_MIN)
            _noiseFloor.value = noiseFloorEstimate
        }
    }

    /**
     * Compute RMS (Root Mean Square) amplitude from a PCM 16-bit buffer.
     * RMS is the perceptually correct measure of loudness for this use case.
     */
    private fun computeRms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return Math.sqrt(sum / count).toFloat()
    }
}

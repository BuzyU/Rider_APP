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

@Singleton
class VoxEngine @Inject constructor() {

    private val TAG = "VoxEngine"

    private var baseThresholdRatio = 2.5f

    private var speedPenalty = 0f

    private val holdTimeMs = 600L

    private val attackTimeMs = 80L

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE_FACTOR = 4
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

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

    private var noiseFloorEstimate = 800f
    private val NOISE_FLOOR_ALPHA = 0.02f
    private val NOISE_FLOOR_MIN = 400f

    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var isInAttack = false

    var onMicStateChange: ((Boolean) -> Unit)? = null

    @Suppress("MissingPermission")
    fun start() {
        if (audioRecord != null || pollJob?.isActive == true) return
        Log.d(TAG, "VOX engine starting")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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

            Log.d(TAG, "Calibrating noise floor...")
            calibrateNoiseFloor(1500L)
            Log.d(TAG, "Noise floor calibrated: $noiseFloorEstimate")

            val buffer = ShortArray(bufferSize / 2)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val rms = computeRms(buffer, read)
                _currentAmplitude.value = rms

                if (!_isMicOpen.value && !pttOverride) {
                    updateNoiseFloor(rms)
                }

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

    fun setPttOverride(open: Boolean) {
        pttOverride = open
        if (open) {
            openMic()
        } else {

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

    fun setSensitivity(sensitivity: Float) {

        baseThresholdRatio = 1.5f + (sensitivity.coerceIn(0f, 1f) * 3.5f)
        Log.d(TAG, "VOX base threshold ratio set to $baseThresholdRatio")
    }

    fun updateSpeed(speedMps: Float) {
        val speedKmh = speedMps * 3.6f
        speedPenalty = when {
            speedKmh > 120f -> 3.0f
            speedKmh > 80f -> 1.5f
            speedKmh > 50f -> 0.5f
            else -> 0f
        }
    }

    private fun evaluateVoxState(rms: Float) {
        val currentRatio = baseThresholdRatio + speedPenalty
        val threshold = noiseFloorEstimate * currentRatio
        val now = System.currentTimeMillis()

        if (rms >= threshold) {

            if (!isInAttack) {
                isInAttack = true
                speechStartTime = now
            }

            val attackElapsed = now - speechStartTime
            if (!_isMicOpen.value && attackElapsed >= attackTimeMs) {

                openMic()
            }

            lastSpeechTime = now
        } else {

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
        val currentRatio = baseThresholdRatio + speedPenalty
        Log.d(TAG, "VOX OPEN (floor=${noiseFloorEstimate.toInt()}, threshold=${(noiseFloorEstimate * currentRatio).toInt()})")
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

            val sorted = samples.sorted()
            val p75idx = (sorted.size * 0.75).toInt().coerceIn(0, sorted.size - 1)
            noiseFloorEstimate = max(sorted[p75idx], NOISE_FLOOR_MIN)
            _noiseFloor.value = noiseFloorEstimate
        }
    }

    private fun computeRms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return Math.sqrt(sum / count).toFloat()
    }
}

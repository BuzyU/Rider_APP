package com.ridervoice.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * Gate controller. The detector core lives in [VoxDetector].
 *
 * NOTE (pre-roll): this engine only flips the LiveKit mute flag, so the first ~60 ms
 * (ATTACK_FRAMES * 20 ms) of an utterance is lost. Faking pre-roll is not possible with a
 * mute gate; feeding VOX from the WebRTC capture itself (optional step 9a) is the real fix.
 */
@Singleton
class VoxEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val TAG = "VoxEngine"

    private val detector = VoxDetector()

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val FRAME = VoxDetector.FRAME
    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val bufferSize = maxOf(minBuf * 4, FRAME * 2 * 10) // >= 200 ms headroom

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isMicOpen = MutableStateFlow(false)
    val isMicOpen: StateFlow<Boolean> = _isMicOpen

    private val _noiseFloor = MutableStateFlow(0f)
    val noiseFloor: StateFlow<Float> = _noiseFloor

    private val _currentAmplitude = MutableStateFlow(0f)
    val currentAmplitude: StateFlow<Float> = _currentAmplitude

    @Volatile private var pttOverride = false
    @Volatile private var voxEnabled = true

    private var pollJob: Job? = null
    private var pttReleaseJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onMicStateChange: ((Boolean) -> Unit)? = null

    @Suppress("MissingPermission")
    fun start() {
        if (audioRecord != null || pollJob?.isActive == true) return
        Log.d(TAG, "VOX engine starting")

        val record: AudioRecord
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise (state=${record.state}) — PTT-only mode")
                record.release()
                return
            }
            preferCommunicationInput(record)
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "VOX AudioRecord start failed — PTT-only mode: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
            return
        }
        audioRecord = record

        pollJob = scope.launch {
            val frame = ShortArray(FRAME)

            Log.d(TAG, "Calibrating noise floor...")
            calibrateNoiseFloor(record, frame)
            Log.d(TAG, "Noise floor calibrated: ${detector.floor}")
            evaluateHardwareRecordingState()

            while (isActive) {
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    val now = System.currentTimeMillis()
                    if (_isMicOpen.value && !pttOverride && lastTxFrameTime > 0L && (now - lastTxFrameTime > 5000L)) {
                        Log.w(TAG, "No TX frames received for >5s while VOX open — safety net closing mic")
                        closeMic()
                    }
                    delay(50)
                    continue
                }
                val read = record.read(frame, 0, FRAME)
                if (read <= 0) { delay(20); continue }

                val rms = detector.rms(frame, read)
                _currentAmplitude.value = rms

                if (!pttOverride) {
                    detector.updateFloor(rms)
                    _noiseFloor.value = detector.floor
                }

                if (voxEnabled && !pttOverride) {
                    when (detector.evaluate(rms, System.currentTimeMillis(), _isMicOpen.value)) {
                        VoxDetector.Decision.OPEN -> openMic(isFromVox = true)
                        VoxDetector.Decision.CLOSE -> closeMic()
                        VoxDetector.Decision.NONE -> {}
                    }
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        pttReleaseJob?.cancel()
        pttReleaseJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
        if (!pttOverride) closeMic()
        Log.d(TAG, "VOX engine stopped")
    }

    fun setPttOverride(open: Boolean) {
        pttReleaseJob?.cancel()
        pttReleaseJob = null
        pttOverride = open
        if (open) {
            openMic(isFromVox = false)
        } else {
            val now = System.currentTimeMillis()
            detector.onPttRelease(now)
            closeMic()
        }
    }

    @Volatile private var hardwarePttEnabled = true
    @Volatile private var hybridVoxMode = true

    fun setHardwarePttEnabled(enabled: Boolean) {
        hardwarePttEnabled = enabled
        Log.d(TAG, "Hardware PTT enabled: $enabled")
    }

    fun setHybridVoxMode(enabled: Boolean) {
        hybridVoxMode = enabled
        Log.d(TAG, "Hybrid VOX mode: $enabled")
    }

    fun setVoxEnabled(enabled: Boolean) {
        voxEnabled = enabled
        evaluateHardwareRecordingState()
        if (!enabled && !pttOverride) closeMic()
    }

    private fun evaluateHardwareRecordingState() {
        val isTransmitting = _isMicOpen.value || pttOverride
        val shouldRecord = voxEnabled && !isTransmitting
        val record = audioRecord ?: return
        if (shouldRecord && record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.startRecording()
                Log.d(TAG, "AudioRecord hardware resumed for VOX monitoring")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
            }
        } else if (!shouldRecord && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.stop()
                Log.d(TAG, "AudioRecord hardware suspended while transmitting/idle")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop AudioRecord: ${e.message}")
            }
        }
    }

    /** sensitivity 0..1, higher = MORE sensitive (lower open threshold). */
    fun setSensitivity(sensitivity: Float) {
        detector.openRatio = 4.0f - 2.0f * sensitivity.coerceIn(0f, 1f)
        Log.d(TAG, "VOX open ratio set to ${detector.openRatio}")
    }

    fun updateSpeed(speedMps: Float) {
        detector.setSpeedKmh(speedMps * 3.6f)
    }

    private fun openMic(isFromVox: Boolean = false) {
        if (_isMicOpen.value) return
        Log.d(TAG, "VOX OPEN (floor=${detector.floor.toInt()}, threshold=${detector.openThreshold.toInt()})")
        _isMicOpen.value = true
        lastTxFrameTime = System.currentTimeMillis()
        evaluateHardwareRecordingState()
        onMicStateChange?.invoke(true)
    }

    private fun closeMic() {
        if (!_isMicOpen.value) return
        Log.d(TAG, "VOX CLOSE")
        _isMicOpen.value = false
        detector.resetAttack()
        evaluateHardwareRecordingState()
        onMicStateChange?.invoke(false)
    }

    @Volatile private var lastTxFrameTime = 0L

    /**
     * Called by LiveKit/WebRTC SamplesReadyCallback on each TX audio frame while transmitting.
     * Keeps VOX active without HAL hardware contention and evaluates speech completion (VOX-CUT-1).
     */
    fun onTxFrame(pcmBytes: ByteArray, sampleRate: Int = 16000) {
        if (!_isMicOpen.value && !pttOverride) return
        val now = System.currentTimeMillis()
        lastTxFrameTime = now

        val numSamples = pcmBytes.size / 2
        if (numSamples <= 0) return
        val frame = ShortArray(numSamples)
        var i = 0
        var j = 0
        while (i < pcmBytes.size - 1 && j < numSamples) {
            val b0 = pcmBytes[i].toInt() and 0xFF
            val b1 = pcmBytes[i + 1].toInt()
            frame[j] = ((b1 shl 8) or b0).toShort()
            i += 2
            j++
        }

        val rms = detector.rms(frame, j)
        _currentAmplitude.value = rms

        if (!pttOverride) {
            detector.updateFloor(rms)
            _noiseFloor.value = detector.floor

            if (voxEnabled) {
                when (detector.evaluate(rms, now, isOpen = true)) {
                    VoxDetector.Decision.CLOSE -> {
                        Log.d(TAG, "VOX speech ended (silence hold expired) -> closing mic")
                        closeMic()
                    }
                    else -> {}
                }
            }
        }
    }

    /** 1 s pass of 20 ms frames; 25th percentile of the high-passed RMS. */
    private fun calibrateNoiseFloor(record: AudioRecord, frame: ShortArray) {
        val samples = ArrayList<Float>(50)
        repeat(50) {
            val read = record.read(frame, 0, FRAME)
            if (read > 0) samples.add(detector.rms(frame, read))
        }
        if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            val idx = (sorted.size * 0.25).toInt().coerceIn(0, sorted.size - 1)
            detector.setFloor(sorted[idx])
            _noiseFloor.value = detector.floor
        }
    }

    /** Steers the VOX capture to the same input the router selected (API 23+). */
    private fun preferCommunicationInput(record: AudioRecord) {
        try {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val wantedType: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (audioManager.communicationDevice?.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceInfo.TYPE_BLE_HEADSET
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceInfo.TYPE_USB_HEADSET
                    else -> null
                }
            } else {
                @Suppress("DEPRECATION")
                when {
                    audioManager.isBluetoothScoOn -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    audioManager.isWiredHeadsetOn -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                    else -> null
                }
            }
            val target = wantedType?.let { t -> inputs.firstOrNull { it.type == t } }
                ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (target != null) {
                val ok = record.setPreferredDevice(target)
                Log.d(TAG, "VOX preferred input type=${target.type} accepted=$ok")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed: ${e.message}")
        }
    }
}

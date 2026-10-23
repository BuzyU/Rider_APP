package com.ridervoice.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ridervoice.utils.RvLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class VoxCaptureMode { AUTO, ADM, OWN }

class VoxCaptureEvaluator(
    var mode: VoxCaptureMode = VoxCaptureMode.AUTO
) {
    private var consecutiveValidSeconds = 0
    var activePath: String = "own"
        private set

    fun onSecondTick(admFramesInLastSecWhileClosed: Int): String {
        activePath = when (mode) {
            VoxCaptureMode.ADM -> "adm"
            VoxCaptureMode.OWN -> "own"
            VoxCaptureMode.AUTO -> {
                if (admFramesInLastSecWhileClosed >= 40) {
                    consecutiveValidSeconds++
                    if (consecutiveValidSeconds >= 3) {
                        "adm"
                    } else {
                        activePath
                    }
                } else {
                    consecutiveValidSeconds = 0
                    "own"
                }
            }
        }
        return activePath
    }

    fun reset() {
        consecutiveValidSeconds = 0
        activePath = if (mode == VoxCaptureMode.ADM) "adm" else "own"
    }
}

@Singleton
class VoxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDeviceRouter: AudioDeviceRouter
) {
    private val TAG = "VoxEngine"

    private val detector = VoxDetector()
    val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO)

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val FRAME = VoxDetector.FRAME
    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val bufferSize = maxOf(minBuf * 4, FRAME * 2 * 10)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isMicOpen = MutableStateFlow(false)
    val isMicOpen: StateFlow<Boolean> = _isMicOpen.asStateFlow()

    private val _noiseFloor = MutableStateFlow(0f)
    val noiseFloor: StateFlow<Float> = _noiseFloor.asStateFlow()

    private val _currentAmplitude = MutableStateFlow(0f)
    val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

    @Volatile private var pttOverride = false
    @Volatile private var voxEnabled = true
    @Volatile private var isRunning = false

    private var pollJob: Job? = null
    private var pttReleaseJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onMicStateChange: ((Boolean) -> Unit)? = null

    @Volatile private var lastTxFrameTime = 0L
    @Volatile private var lastRestartTime = 0L
    private var admFramesThisSec = 0
    private var lastEvalTime = System.currentTimeMillis()

    fun setCaptureMode(mode: VoxCaptureMode) {
        evaluator.mode = mode
        if (mode == VoxCaptureMode.ADM) {
            stopOwnAudioRecord()
        } else if (mode == VoxCaptureMode.OWN && isRunning && audioRecord == null) {
            startOwnAudioRecord()
        }
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "VOX engine starting")

        if (evaluator.activePath == "own") {
            startOwnAudioRecord()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        stopOwnAudioRecord()
        pttReleaseJob?.cancel()
        pttReleaseJob = null
        if (!pttOverride) closeMic()
        Log.d(TAG, "VOX engine stopped")
    }

    private fun startOwnAudioRecord() {
        if (audioRecord != null || pollJob?.isActive == true) return
        try {
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            var rec = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rec.addOnRoutingChangedListener(
                    AudioRecord.OnRoutingChangedListener { r ->
                        val dev = (r as? AudioRecord)?.routedDevice
                        audioDeviceRouter.onInputRoutingChanged(dev)
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            preferCommunicationInput(rec)
            rec.startRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceRouter.onInputRoutingChanged(rec.routedDevice)
            }
            audioRecord = rec

            pollJob = scope.launch {
                val frame = ShortArray(FRAME)
                calibrateNoiseFloor(rec, frame)

                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (_isMicOpen.value) {
                        delay(20)
                        continue
                    }
                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        delay(50)
                        continue
                    }
                    val read = rec.read(frame, 0, FRAME)
                    if (read <= 0) { delay(20); continue }

                    val rms = detector.rms(frame, read, SAMPLE_RATE)
                    _currentAmplitude.value = rms

                    if (!pttOverride && !_isMicOpen.value) {
                        detector.updateFloor(rms)
                        _noiseFloor.value = detector.floor
                    }

                    if (voxEnabled && !pttOverride) {
                        when (detector.evaluate(rms, now, false)) {
                            VoxDetector.Decision.OPEN -> openMic(isFromVox = true)
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startOwnAudioRecord failed: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun stopOwnAudioRecord() {
        pollJob?.cancel()
        pollJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
    }

    fun onTxFrame(pcmBytes: ByteArray, sampleRate: Int = 16000) {
        val now = System.currentTimeMillis()
        lastTxFrameTime = now

        if (!_isMicOpen.value) {
            admFramesThisSec++
        }

        if (now - lastEvalTime >= 1000L) {
            val previousPath = evaluator.activePath
            val newPath = evaluator.onSecondTick(admFramesThisSec)
            RvLog.vox("path=$newPath frames_per_s=$admFramesThisSec rate=$sampleRate")
            admFramesThisSec = 0
            lastEvalTime = now

            if (previousPath != newPath) {
                if (newPath == "adm") {
                    stopOwnAudioRecord()
                } else if (isRunning) {
                    startOwnAudioRecord()
                }
            }
        }

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

        val rms = detector.rms(frame, j, sampleRate)
        _currentAmplitude.value = rms

        if (!pttOverride && voxEnabled) {
            if (!_isMicOpen.value) {
                if (evaluator.activePath == "adm") {
                    detector.updateFloor(rms)
                    _noiseFloor.value = detector.floor
                    when (detector.evaluate(rms, now, isOpen = false)) {
                        VoxDetector.Decision.OPEN -> openMic(isFromVox = true)
                        else -> {}
                    }
                }
            } else {
                when (detector.evaluate(rms, now, isOpen = true)) {
                    VoxDetector.Decision.CLOSE -> closeMic()
                    else -> {}
                }
            }
        }
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

    fun setHardwarePttEnabled(enabled: Boolean) {
        // preserved for compatibility
    }

    fun setHybridVoxMode(enabled: Boolean) {
        // preserved for compatibility
    }

    fun setVoxEnabled(enabled: Boolean) {
        voxEnabled = enabled
        if (!enabled && !pttOverride) closeMic()
    }

    fun setSensitivity(sensitivity: Float) {
        detector.openRatio = 4.0f - 2.0f * sensitivity.coerceIn(0f, 1f)
    }

    fun updateSpeed(speedMps: Float) {
        detector.setSpeedKmh(speedMps * 3.6f)
    }

    private fun openMic(isFromVox: Boolean = false) {
        if (_isMicOpen.value) return
        _isMicOpen.value = true
        lastTxFrameTime = System.currentTimeMillis()
        onMicStateChange?.invoke(true)
    }

    private fun closeMic() {
        if (!_isMicOpen.value) return
        _isMicOpen.value = false
        detector.resetAttack()
        onMicStateChange?.invoke(false)
    }

    fun restartVox() {
        val now = System.currentTimeMillis()
        if (now - lastRestartTime < 10000L) {
            updatePreferredDevice()
            return
        }
        lastRestartTime = now
        updatePreferredDevice()
        if (evaluator.activePath == "own" && (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED)) {
            startOwnAudioRecord()
        }
    }

    fun updatePreferredDevice() {
        val record = audioRecord ?: return
        preferCommunicationInput(record)
    }

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
            if (target != null) {
                record.setPreferredDevice(target)
            } else {
                record.setPreferredDevice(null)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceRouter.onInputRoutingChanged(record.routedDevice)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed: ${e.message}")
        }
    }

    private fun calibrateNoiseFloor(record: AudioRecord, frame: ShortArray) {
        val samples = ArrayList<Float>(50)
        var attempts = 0
        while (samples.size < 50 && attempts < 200) {
            val read = record.read(frame, 0, FRAME)
            attempts++
            if (read > 0) {
                val rms = detector.rms(frame, read, SAMPLE_RATE)
                if (rms > 0f) samples.add(rms)
            }
        }
        if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            val idx = (sorted.size * 0.25).toInt().coerceIn(0, sorted.size - 1)
            detector.setFloor(sorted[idx])
            _noiseFloor.value = detector.floor
        }
    }
}

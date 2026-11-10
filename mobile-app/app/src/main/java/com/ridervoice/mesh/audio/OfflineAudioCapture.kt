package com.ridervoice.mesh.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun calculateAudioRecordBufferSize(minBuf: Int): Int =
    maxOf(minBuf, MeshConfig.BYTES_PER_FRAME * 4)

interface AudioRecordSource {
    val state: Int
    val audioSessionId: Int
    fun startRecording()
    fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun stop()
    fun release()
}

class AndroidAudioRecordSource(private val rec: AudioRecord) : AudioRecordSource {
    override val state: Int get() = rec.state
    override val audioSessionId: Int get() = rec.audioSessionId
    override fun startRecording() = rec.startRecording()
    override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
        rec.read(audioData, offsetInBytes, sizeInBytes)
    override fun stop() = rec.stop()
    override fun release() = rec.release()
}

sealed interface CaptureStart {
    object Started : CaptureStart
    object NoPermission : CaptureStart
    object GateClosed : CaptureStart
    object NotOwner : CaptureStart
    object HardwareFailed : CaptureStart
}

/**
 * Offline 16 kHz mono 16-bit PCM capture loop (20 ms frames = 320 samples = 640 bytes).
 *
 * Strictly gated: never creates an [AudioRecord] unless gateAllowed, canClaimAudio,
 * and hasMicPermission all return true.
 * Manages AEC, NS, and AGC effects lifecycle, releasing them on stop.
 */
class OfflineAudioCapture(
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val canClaimAudio: () -> Boolean = { true },
    private val hasMicPermission: () -> Boolean = { true },
    private val onHardwareBusy: () -> Unit = {},
    private val onCaptureError: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val audioRecordFactory: (() -> AudioRecordSource?)? = null,
    private val onPcmFrame: (ByteArray) -> Unit
) {
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var micEnabled: Boolean = false
    private var captureScope: CoroutineScope? = null
    private var recordJob: Job? = null
    private companion object {
        const val MAX_EMPTY_READS = 50
    }

    private var audioRecordSource: AudioRecordSource? = null
    var frameGate: ((ByteArray) -> Boolean)? = null
    var onFrameWithTimestamp: ((ByteArray, Long) -> Unit)? = null

    // Audio effects handles
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        if (!enabled) {
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): CaptureStart {
        if (!gateAllowed() || !micEnabled) return CaptureStart.GateClosed
        if (!canClaimAudio()) return CaptureStart.NotOwner
        if (!hasMicPermission()) return CaptureStart.NoPermission

        if (recordJob?.isActive == true) return CaptureStart.Started

        val recSource: AudioRecordSource = if (audioRecordFactory != null) {
            audioRecordFactory.invoke() ?: return CaptureStart.HardwareFailed
        } else {
            val minBuf = AudioRecord.getMinBufferSize(
                MeshConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return CaptureStart.HardwareFailed

            val rec = try {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(MeshConfig.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(calculateAudioRecordBufferSize(minBuf))
                        .build()
                } else {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MeshConfig.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        calculateAudioRecordBufferSize(minBuf)
                    )
                }
            } catch (e: Exception) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioCapture", "AudioRecord creation failed: ${e.javaClass.simpleName}")
                onCaptureError("AudioRecord creation failed: ${e.message}")
                return CaptureStart.HardwareFailed
            }

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                onCaptureError("AudioRecord uninitialized")
                return CaptureStart.HardwareFailed
            }

            if (MeshConfig.USE_PLATFORM_EFFECTS_ON_VOICE_COMM_SOURCE) {
                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }
                    }
                }
            }
            AndroidAudioRecordSource(rec)
        }

        if (recSource.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recSource.release() }
            onCaptureError("recSource state not initialized")
            return CaptureStart.HardwareFailed
        }

        audioRecordSource = recSource
        _isCapturing.value = true
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        captureScope = scope
        recordJob = scope.launch {
            val previousPriority = runCatching { android.os.Process.getThreadPriority(0) }.getOrNull()
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val frameBuf = ByteArray(MeshConfig.BYTES_PER_FRAME)
            var emptyReadCount = 0
            try {
                recSource.startRecording()
                while (isActive && micEnabled && gateAllowed() && canClaimAudio()) {
                    var offset = 0
                    var failed = false
                    while (offset < frameBuf.size && isActive) {
                        val read = recSource.read(frameBuf, offset, frameBuf.size - offset)
                        if (read < 0) {
                            failed = true
                            onCaptureError("AudioRecord.read error $read")
                            break
                        }
                        if (read == 0) {
                            if (++emptyReadCount >= MAX_EMPTY_READS) {
                                failed = true
                                onCaptureError("AudioRecord.read returned 0 x$MAX_EMPTY_READS")
                                break
                            }
                            delay(10L)                      // keep `offset`: the partial frame is NOT discarded
                            continue
                        }
                        emptyReadCount = 0
                        offset += read
                    }
                    if (failed) {
                        onHardwareBusy()
                        break
                    }
                    if (offset == frameBuf.size && micEnabled && (frameGate?.invoke(frameBuf) ?: true)) {
                        val nanos = runCatching { android.os.SystemClock.elapsedRealtimeNanos() }.getOrNull()?.takeIf { it != 0L } ?: System.nanoTime()
                        val cb = onFrameWithTimestamp
                        if (cb != null) {
                            cb(frameBuf.copyOf(), nanos)
                        } else {
                            onPcmFrame(frameBuf.copyOf())
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: java.io.IOException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioCapture", "IOException: ${e.javaClass.simpleName}")
                onCaptureError("IOException: ${e.message}")
                onHardwareBusy()
            } catch (e: IllegalStateException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioCapture", "IllegalStateException: ${e.javaClass.simpleName}")
                onCaptureError("IllegalStateException: ${e.message}")
                onHardwareBusy()
            } catch (e: SecurityException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioCapture", "SecurityException: ${e.javaClass.simpleName}")
                onCaptureError("SecurityException: ${e.message}")
                onHardwareBusy()
            } finally {
                previousPriority?.let { p -> runCatching { android.os.Process.setThreadPriority(p) } }
                releaseInternal(recSource)
            }
        }
        return CaptureStart.Started
    }

    @Synchronized
    fun stop() {
        _isCapturing.value = false
        recordJob?.cancel()
        recordJob = null
        captureScope?.cancel()
        captureScope = null
        audioRecordSource?.let { releaseInternal(it) }
        audioRecordSource = null
        releaseEffects()
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null
    }

    private fun releaseInternal(rec: AudioRecordSource) {
        _isCapturing.value = false
        releaseEffects()
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
}

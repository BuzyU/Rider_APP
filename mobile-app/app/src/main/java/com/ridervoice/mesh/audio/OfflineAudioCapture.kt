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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Offline 16 kHz mono 16-bit PCM capture loop (20 ms frames = 320 samples = 640 bytes).
 *
 * Strictly gated: never creates an [AudioRecord] unless both [gateAllowed] and [canClaimAudio]
 * return true.
 */
class OfflineAudioCapture(
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val canClaimAudio: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPcmFrame: (ByteArray) -> Unit
) {
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var micEnabled: Boolean = false
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        if (!enabled) {
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (!gateAllowed() || !canClaimAudio() || !micEnabled) {
            return false
        }
        if (recordJob?.isActive == true) return true

        val minBuf = AudioRecord.getMinBufferSize(
            MeshConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MeshConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(MeshConfig.BYTES_PER_FRAME * 4)
            )
        } catch (_: Throwable) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }

        runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
        }

        audioRecord = rec
        _isCapturing.value = true
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        recordJob = scope.launch {
            val frameBuf = ByteArray(MeshConfig.BYTES_PER_FRAME)
            try {
                rec.startRecording()
                while (isActive && micEnabled && gateAllowed() && canClaimAudio()) {
                    var offset = 0
                    while (offset < frameBuf.size && isActive) {
                        val read = rec.read(frameBuf, offset, frameBuf.size - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset == frameBuf.size && micEnabled) {
                        onPcmFrame(frameBuf.copyOf())
                    }
                }
            } catch (_: Throwable) {
                // Handled in finally
            } finally {
                releaseInternal(rec)
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        _isCapturing.value = false
        recordJob?.cancel()
        recordJob = null
        audioRecord?.let { releaseInternal(it) }
        audioRecord = null
    }

    private fun releaseInternal(rec: AudioRecord) {
        _isCapturing.value = false
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
}


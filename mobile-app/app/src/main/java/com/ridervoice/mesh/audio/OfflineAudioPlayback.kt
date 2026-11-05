package com.ridervoice.mesh.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Offline 16 kHz mono 16-bit PCM playback driven by [MultiSourceJitterBufferManager] at 20 ms ticks.
 *
 * Playout thread runs at [android.os.Process.THREAD_PRIORITY_URGENT_AUDIO].
 * Handles AudioTrack initialization failures and underruns gracefully.
 */
class OfflineAudioPlayback(
    private val jitterManager: MultiSourceJitterBufferManager,
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onUnderrun: (Long) -> Unit = {}
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    @Volatile
    private var deafened: Boolean = false
    private var playJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var underrunCount: Long = 0L

    fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
    }

    @Synchronized
    fun start(): Boolean {
        if (!gateAllowed()) return false
        if (playJob?.isActive == true) return true

        val minBuf = AudioTrack.getMinBufferSize(
            MeshConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(MeshConfig.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(MeshConfig.BYTES_PER_FRAME * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack creation failed: ${e.javaClass.simpleName}")
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        audioTrack = track
        _isPlaying.value = true
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        playJob = scope.launch {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            val silenceBuf = ByteArray(MeshConfig.BYTES_PER_FRAME)
            try {
                track.play()
                while (isActive && gateAllowed()) {
                    val (mixed, speakers) = jitterManager.pollMixedFrame()
                    _activeSpeakers.value = speakers
                    if (mixed != null && !deafened) {
                        track.write(mixed, 0, mixed.size)
                    } else {
                        // Insert silence to keep AudioTrack buffer from starving
                        track.write(silenceBuf, 0, silenceBuf.size)
                        underrunCount++
                        onUnderrun(underrunCount)
                        delay(MeshConfig.FRAME_MS.toLong())
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: java.io.IOException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack write I/O error: ${e.javaClass.simpleName}")
            } catch (e: IllegalStateException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack released or stopped: ${e.javaClass.simpleName}")
            } finally {
                releaseInternal(track)
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        _isPlaying.value = false
        _activeSpeakers.value = emptySet()
        playJob?.cancel()
        playJob = null
        audioTrack?.let { releaseInternal(it) }
        audioTrack = null
        jitterManager.clear()
    }

    private fun releaseInternal(track: AudioTrack) {
        _isPlaying.value = false
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

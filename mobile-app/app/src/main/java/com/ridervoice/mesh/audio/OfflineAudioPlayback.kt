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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

fun calculateAudioTrackBufferSize(minBuf: Int): Int =
    maxOf(minBuf, MeshConfig.BYTES_PER_FRAME * 2)

interface AudioTrackSink {
    val state: Int
    val underrunCount: Int get() = 0
    fun play()
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun stop()
    fun release()
}

class AndroidAudioTrackSink(private val track: AudioTrack) : AudioTrackSink {
    override val state: Int get() = track.state
    override val underrunCount: Int
        get() = if (android.os.Build.VERSION.SDK_INT >= 24) {
            runCatching { track.underrunCount }.getOrDefault(0)
        } else {
            0
        }
    override fun play() = track.play()
    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
        track.write(audioData, offsetInBytes, sizeInBytes)
    override fun stop() = track.stop()
    override fun release() = track.release()
}

/**
 * Offline 16 kHz mono 16-bit PCM playback driven by [MultiSourceJitterBufferManager] at 20 ms ticks.
 *
 * Playout thread runs at [android.os.Process.THREAD_PRIORITY_URGENT_AUDIO].
 * Enforces 20 ms monotonic tick pacing so buffered frames are never burst-drained when speech begins,
 * and handles AudioTrack initialization failures and true mid-stream underruns gracefully.
 */
class OfflineAudioPlayback(
    private val jitterManager: MultiSourceJitterBufferManager,
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onUnderrun: (Long) -> Unit = {},
    private val audioTrackFactory: (() -> AudioTrackSink?)? = null,
    private val nanoClock: () -> Long = { System.nanoTime() }
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    @Volatile
    private var deafened: Boolean = false
    private var playbackScope: CoroutineScope? = null
    private var playJob: Job? = null
    private var audioTrackSink: AudioTrackSink? = null
    var concealedMidTalkspurt: Long = 0L
        private set
    var trackUnderrunCount: Long = 0L
        private set
    val underrunCount: Long get() = concealedMidTalkspurt

    fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
    }

    @Synchronized
    fun start(): Boolean {
        if (!gateAllowed()) return false
        if (playJob?.isActive == true) return true

        val sink: AudioTrackSink = if (audioTrackFactory != null) {
            audioTrackFactory.invoke() ?: return false
        } else {
            val minBuf = try {
                AudioTrack.getMinBufferSize(
                    MeshConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                -1
            }
            if (minBuf <= 0) return false

            val track = try {
                val builder = AudioTrack.Builder()
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
                    .setBufferSizeInBytes(calculateAudioTrackBufferSize(minBuf))
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                builder.build()
            } catch (e: Exception) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack creation failed: ${e.javaClass.simpleName}")
                return false
            }
            AndroidAudioTrackSink(track)
        }

        if (sink.state != AudioTrack.STATE_INITIALIZED) {
            try { sink.release() } catch (_: Exception) {}
            return false
        }

        audioTrackSink = sink
        _isPlaying.value = true
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        playbackScope = scope
        val frameNs = MeshConfig.FRAME_MS.toLong() * 1_000_000L
        val maxDriftNs = frameNs * 3L

        playJob = scope.launch {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            val silenceBuf = ByteArray(MeshConfig.BYTES_PER_FRAME)
            var nextTickNs = nanoClock()
            var lastTrackUnderrunCheckNs = 0L
            try {
                sink.write(silenceBuf, 0, silenceBuf.size)
                sink.play()
                while (isActive && gateAllowed()) {
                    val isDeaf = deafened
                    val (mixed, speakers) = if (isDeaf) {
                        jitterManager.pollMixedFrame() // advance jitter buffer while deafened
                        Pair(null, emptySet())
                    } else {
                        jitterManager.pollMixedFrame()
                    }
                    _activeSpeakers.value = speakers

                    val hadMidTalkspurt = jitterManager.hadMidTalkspurtConcealment()
                    val toWrite = if (mixed != null && !isDeaf) mixed else silenceBuf
                    val written = sink.write(toWrite, 0, toWrite.size)
                    if (written < 0) {
                        concealedMidTalkspurt++
                        onUnderrun(concealedMidTalkspurt)
                    } else if (!isDeaf && mixed != null && speakers.isEmpty() && hadMidTalkspurt) {
                        // A primed talker stream had to synthesize a concealed PLC frame mid-talkspurt
                        concealedMidTalkspurt++
                        onUnderrun(concealedMidTalkspurt)
                    }

                    nextTickNs += frameNs
                    val nowNs = nanoClock()
                    if (nowNs - lastTrackUnderrunCheckNs >= 1_000_000_000L) {
                        lastTrackUnderrunCheckNs = nowNs
                        trackUnderrunCount = sink.underrunCount.toLong()
                    }
                    val remainingNs = nextTickNs - nowNs
                    if (remainingNs >= 1_000_000L) {
                        val delayMs = remainingNs / 1_000_000L
                        delay(delayMs)
                    } else {
                        if (nowNs - nextTickNs > maxDriftNs) {
                            // Resync monotonic clock after OS thread scheduling hiccup or blocking write
                            nextTickNs = nowNs
                        }
                        yield()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: java.io.IOException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack write I/O error: ${e.javaClass.simpleName}")
            } catch (e: IllegalStateException) {
                com.ridervoice.mesh.MeshLog.w("OfflineAudioPlayback", "AudioTrack released or stopped: ${e.javaClass.simpleName}")
            } finally {
                releaseInternal(sink)
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
        playbackScope?.cancel()
        playbackScope = null
        audioTrackSink?.let { releaseInternal(it) }
        audioTrackSink = null
        jitterManager.clear()
    }

    private fun releaseInternal(sink: AudioTrackSink) {
        _isPlaying.value = false
        try { sink.stop() } catch (_: Exception) {}
        try { sink.release() } catch (_: Exception) {}
    }
}

package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Result of pulling one 20ms audio frame from a [SourceJitterBuffer].
 */
sealed class PlayoutFrame {
    /** Buffering initial playout delay or buffer currently idle. */
    data object Buffering : PlayoutFrame()
    /** Authentic ordered audio frame from the source. */
    data class Audio(val sequence: Int, val pcmBytes: ByteArray) : PlayoutFrame()
    /** Concealed frame (attenuated previous frame or comfort silence) for a missing sequence. */
    data class Concealed(val missingSequence: Int, val pcmBytes: ByteArray) : PlayoutFrame()
}

/**
 * Single-source adaptive/bounded jitter buffer with sequence reordering, target playout delay,
 * late packet dropping, missing-packet detection, and packet-loss concealment (PLC).
 */
class SourceJitterBuffer(
    val sourceId: String,
    private val targetDelayFrames: Int = 3, // 3 * 20ms = 60ms within 40-120ms target window
    private val maxQueueFrames: Int = 16,   // 320ms max queue bound
    private val frameBytes: Int = MeshConfig.BYTES_PER_FRAME,
    private val clock: () -> Long
) {
    private val queue = TreeMap<Int, ByteArray>()
    private var primed = false
    private var nextExpectedSeq: Int = 0
    private var consecutiveMissing: Int = 0
    private var lastPlayedPcm: ByteArray? = null

    var lastEnqueueTimeMs: Long = clock()
        private set
    var lateDroppedCount: Long = 0L
        private set
    var overflowDroppedCount: Long = 0L
        private set
    var concealedCount: Long = 0L
        private set
    var deliveredCount: Long = 0L
        private set

    @Synchronized
    fun enqueue(sequence: Int, pcmBytes: ByteArray) {
        lastEnqueueTimeMs = clock()
        if (primed && sequence < nextExpectedSeq) {
            lateDroppedCount++
            return
        }
        if (queue.containsKey(sequence)) {
            return
        }
        queue[sequence] = normalizeFrameSize(pcmBytes)

        // Enforce bounded queue size: drop oldest frames if queue overflows
        while (queue.size > maxQueueFrames) {
            val droppedKey = queue.firstKey()
            queue.remove(droppedKey)
            overflowDroppedCount++
            if (primed && nextExpectedSeq <= droppedKey) {
                nextExpectedSeq = (queue.firstKey() ?: (droppedKey + 1))
            }
        }

        if (!primed && queue.size >= targetDelayFrames) {
            primed = true
            nextExpectedSeq = queue.firstKey()
        }
    }

    /**
     * Pulls the next 20ms frame in sequence order.
     */
    @Synchronized
    fun pollFrame(): PlayoutFrame {
        if (!primed) {
            return PlayoutFrame.Buffering
        }
        if (queue.isEmpty() && consecutiveMissing >= targetDelayFrames) {
            // Stream went idle; re-prime on next burst
            primed = false
            consecutiveMissing = 0
            return PlayoutFrame.Buffering
        }

        val seq = nextExpectedSeq
        val exact = queue.remove(seq)
        if (exact != null) {
            nextExpectedSeq = seq + 1
            consecutiveMissing = 0
            lastPlayedPcm = exact
            deliveredCount++
            return PlayoutFrame.Audio(seq, exact)
        }

        // Missing packet detected
        nextExpectedSeq = seq + 1
        consecutiveMissing++
        concealedCount++
        val concealed = synthesizePlcFrame(consecutiveMissing)
        return PlayoutFrame.Concealed(seq, concealed)
    }

    @Synchronized
    fun bufferedFrameCount(): Int = queue.size

    @Synchronized
    fun isPrimed(): Boolean = primed

    private fun synthesizePlcFrame(missingStreak: Int): ByteArray {
        val prev = lastPlayedPcm
        // For the first 2 missing frames (40ms), attenuate the last good frame; beyond that, insert silence.
        if (prev == null || missingStreak > 2) {
            return ByteArray(frameBytes)
        }
        val factor = if (missingStreak == 1) 0.6f else 0.25f
        val out = ByteArray(frameBytes)
        val inBuf = ByteBuffer.wrap(prev).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val samples = frameBytes / 2
        for (i in 0 until samples) {
            val s = (inBuf.short * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outBuf.putShort(s)
        }
        return out
    }

    private fun normalizeFrameSize(pcm: ByteArray): ByteArray {
        return when {
            pcm.size == frameBytes -> pcm.copyOf()
            pcm.size > frameBytes -> pcm.copyOf(frameBytes)
            else -> ByteArray(frameBytes).also { System.arraycopy(pcm, 0, it, 0, pcm.size) }
        }
    }
}

/**
 * Manages one [SourceJitterBuffer] per remote `originalSourceDeviceId`, mixes concurrent active
 * sources with 16-bit saturating addition, and cleans up timed-out sources.
 */
class MultiSourceJitterBufferManager(
    private val targetDelayFrames: Int = 3,
    private val maxQueueFrames: Int = 16,
    private val sourceTimeoutMs: Long = MeshConfig.NEIGHBOR_TIMEOUT_MS,
    private val clock: () -> Long
) {
    private val buffers = LinkedHashMap<String, SourceJitterBuffer>()

    @Synchronized
    fun onVoicePacket(sourceId: String, sequence: Int, pcmBytes: ByteArray) {
        val buf = buffers.getOrPut(sourceId) {
            SourceJitterBuffer(
                sourceId = sourceId,
                targetDelayFrames = targetDelayFrames,
                maxQueueFrames = maxQueueFrames,
                clock = clock
            )
        }
        buf.enqueue(sequence, pcmBytes)
    }

    /**
     * Pulls one frame from each active source, mixes them into a single 20ms PCM16 frame,
     * and returns the mixed audio plus the set of sources that contributed audio in this tick.
     * Also evicts timed-out source buffers.
     */
    @Synchronized
    fun pollMixedFrame(): Pair<ByteArray?, Set<String>> {
        evictExpiredSources()
        if (buffers.isEmpty()) return null to emptySet()

        val activeFrames = ArrayList<ByteArray>()
        val speakingSources = LinkedHashSet<String>()

        for ((sourceId, buf) in buffers) {
            when (val f = buf.pollFrame()) {
                is PlayoutFrame.Audio -> {
                    activeFrames.add(f.pcmBytes)
                    speakingSources.add(sourceId)
                }
                is PlayoutFrame.Concealed -> {
                    activeFrames.add(f.pcmBytes)
                }
                PlayoutFrame.Buffering -> Unit
            }
        }

        if (activeFrames.isEmpty()) return null to emptySet()
        if (activeFrames.size == 1) return activeFrames[0] to speakingSources

        return mixPcm16Frames(activeFrames) to speakingSources
    }

    @Synchronized
    fun activeSourceIds(): Set<String> {
        evictExpiredSources()
        return buffers.keys.toSet()
    }

    @Synchronized
    fun bufferFor(sourceId: String): SourceJitterBuffer? = buffers[sourceId]

    @Synchronized
    fun clear() = buffers.clear()

    @Synchronized
    fun evictExpiredSources(): List<String> {
        val now = clock()
        val removed = ArrayList<String>()
        val it = buffers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastEnqueueTimeMs > sourceTimeoutMs) {
                removed.add(entry.key)
                it.remove()
            }
        }
        return removed
    }

    companion object {
        fun mixPcm16Frames(frames: List<ByteArray>, frameBytes: Int = MeshConfig.BYTES_PER_FRAME): ByteArray {
            val out = ByteArray(frameBytes)
            val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            val inBufs = frames.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            val samples = frameBytes / 2
            for (i in 0 until samples) {
                var acc = 0
                for (b in inBufs) {
                    if (b.remaining() >= 2) acc += b.short.toInt()
                }
                val clamped = acc.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                outBuf.putShort(clamped)
            }
            return out
        }
    }
}


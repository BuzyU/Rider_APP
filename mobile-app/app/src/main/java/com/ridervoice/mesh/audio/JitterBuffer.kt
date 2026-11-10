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
    data class Concealed(
        val missingSequence: Int,
        val pcmBytes: ByteArray,
        val isMidTalkspurt: Boolean = false
    ) : PlayoutFrame()
}

/**
 * Single-source jitter buffer with fixed 3-frame target playout delay (60 ms),
 * sequence reordering with 32-bit wrap-around support, late packet dropping,
 * missing-packet detection, and packet-loss concealment (PLC).
 */
class SourceJitterBuffer(
    val sourceId: String,
    private val targetDelayFrames: Int = 3, // Fixed 3-frame target (3 * 20ms = 60ms)
    private val maxQueueFrames: Int = 8,    // 160ms max queue bound
    private val frameBytes: Int = MeshConfig.BYTES_PER_FRAME,
    val epoch: Int = 0,
    val seqBits: Int = 32,
    private val clock: () -> Long
) {
    companion object {
        fun seqDiff(a: Int, b: Int, bits: Int = 32): Int {
            if (bits == 32) return a - b
            val mask = (1 shl bits) - 1
            val half = 1 shl (bits - 1)
            val d = (a - b) and mask
            return if (d >= half) d - (1 shl bits) else d
        }
    }

    // Circular sequence comparison for wrap-around handling
    private val queue = TreeMap<Int, ByteArray> { a, b ->
        val diff = seqDiff(a, b, seqBits)
        when {
            diff < 0 -> -1
            diff > 0 -> 1
            else -> 0
        }
    }
    private var primed = false
    private var nextExpectedSeq: Int = 0
    private var consecutiveMissing: Int = 0
    private var lastPlayedPcm: ByteArray? = null
    private val enqueueTimes = HashMap<Int, Long>()
    private var overTicks: Int = 0
    private var lastTrimMs: Long = 0L

    private var currentTargetFrames: Int = targetDelayFrames
    private var jitterEmaMs: Double = 0.0
    private var prevEnqueueTimeMs: Long = 0L
    private var stableArrivalsStartMs: Long = 0L
    private var lastTargetAdjustmentTimeMs: Long = 0L

    val depthFrames: Int
        @Synchronized get() = queue.size
    val targetFrames: Int
        get() = currentTargetFrames
    @Volatile
    var lastPlayoutDelayMs: Long = 0L
        private set

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
        val nowMs = clock()
        lastEnqueueTimeMs = nowMs
        if (primed && prevEnqueueTimeMs > 0L) {
            val interArrival = nowMs - prevEnqueueTimeMs
            if (interArrival >= 0L) {
                val deviation = Math.abs(interArrival.toDouble() - 20.0)
                jitterEmaMs = if (jitterEmaMs == 0.0) deviation else ((15.0 * jitterEmaMs + deviation) / 16.0)
                val calculated = (Math.ceil((2.0 * jitterEmaMs) / 20.0).toInt() + 1).coerceIn(2, 6)
                if (currentTargetFrames > calculated) {
                    if (stableArrivalsStartMs == 0L) {
                        stableArrivalsStartMs = nowMs
                    } else if (nowMs - stableArrivalsStartMs >= 2000L) {
                        currentTargetFrames--
                        stableArrivalsStartMs = nowMs
                    }
                } else if (calculated > currentTargetFrames) {
                    currentTargetFrames = calculated
                    stableArrivalsStartMs = nowMs
                } else {
                    if (stableArrivalsStartMs == 0L) {
                        stableArrivalsStartMs = nowMs
                    }
                }
            }
        }
        prevEnqueueTimeMs = nowMs

        val diff = seqDiff(sequence, nextExpectedSeq, seqBits)
        if (primed && diff < 0) {
            lateDroppedCount++
            val calculated = (Math.ceil((2.0 * jitterEmaMs) / 20.0).toInt() + 1).coerceIn(2, 6)
            currentTargetFrames = maxOf(currentTargetFrames + 1, calculated).coerceIn(2, 6)
            stableArrivalsStartMs = nowMs
            return
        }
        if (queue.containsKey(sequence)) {
            return
        }
        enqueueTimes[sequence] = lastEnqueueTimeMs
        queue[sequence] = normalizeFrameSize(pcmBytes)

        // Enforce bounded queue size: drop oldest frames if queue overflows
        while (queue.size > maxQueueFrames) {
            val droppedKey = queue.firstKey()
            queue.remove(droppedKey)
            enqueueTimes.remove(droppedKey)
            overflowDroppedCount++
            if (primed && seqDiff(nextExpectedSeq, droppedKey, seqBits) <= 0) {
                if (queue.isNotEmpty()) {
                    nextExpectedSeq = queue.firstKey()
                } else {
                    nextExpectedSeq = droppedKey + 1
                }
            }
        }

        if (!primed && queue.size >= currentTargetFrames) {
            primed = true
            nextExpectedSeq = queue.firstKey()
        }
    }

    private fun trimToTarget() {
        while (queue.size > currentTargetFrames) {
            val oldest = queue.firstKey()
            queue.remove(oldest)
            enqueueTimes.remove(oldest)
            overflowDroppedCount++
            if (primed && seqDiff(nextExpectedSeq, oldest, seqBits) <= 0) {
                nextExpectedSeq = if (queue.isNotEmpty()) queue.firstKey() else oldest + 1
            }
        }
    }

    private fun maybeCatchUp() {
        // standing-delay catch-up: if queue.size > target + 1 for >= 25 consecutive ticks (500 ms): drop ONE oldest frame, at most 1 per 1000 ms
        if (queue.size > currentTargetFrames + 1) {
            overTicks++
            val nowMs = clock()
            if (overTicks >= 25 && nowMs - lastTrimMs >= 1000L) {
                if (queue.isNotEmpty()) {
                    val oldest = queue.firstKey()
                    queue.remove(oldest)
                    enqueueTimes.remove(oldest)
                    overflowDroppedCount++
                    if (primed && seqDiff(nextExpectedSeq, oldest, seqBits) <= 0) {
                        nextExpectedSeq = if (queue.isNotEmpty()) queue.firstKey() else oldest + 1
                    }
                }
                lastTrimMs = nowMs
                overTicks = 0
            }
        } else if (queue.size <= currentTargetFrames) {
            overTicks = 0
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

        val head = if (queue.isEmpty()) null else queue.firstKey()
        if (head != null && head != nextExpectedSeq) {
            val gap = seqDiff(head, nextExpectedSeq, seqBits)
            if (gap >= maxOf(3, currentTargetFrames)) {
                // long hole: do not conceal tick by tick!
                trimToTarget()
                val newHead = if (queue.isEmpty()) head else queue.firstKey()
                nextExpectedSeq = newHead
                consecutiveMissing++
                concealedCount++
                val calculated = (Math.ceil((2.0 * jitterEmaMs) / 20.0).toInt() + 1).coerceIn(2, 6)
                currentTargetFrames = maxOf(currentTargetFrames + 1, calculated).coerceIn(2, 6)
                stableArrivalsStartMs = clock()
                val concealed = synthesizePlcFrame(consecutiveMissing)
                return PlayoutFrame.Concealed(newHead - 1, concealed, isMidTalkspurt = true)
            }
        }

        val seq = nextExpectedSeq
        val exact = queue.remove(seq)
        if (exact != null) {
            val enq = enqueueTimes.remove(seq)
            if (enq != null) {
                val d = (clock() - enq).coerceAtLeast(0L)
                lastPlayoutDelayMs = if (lastPlayoutDelayMs == 0L) d else (0.9 * lastPlayoutDelayMs + 0.1 * d).toLong()
            }
            nextExpectedSeq = seq + 1
            consecutiveMissing = 0
            lastPlayedPcm = exact
            deliveredCount++
            maybeCatchUp()
            return PlayoutFrame.Audio(seq, exact)
        }

        if (queue.isEmpty() && consecutiveMissing >= currentTargetFrames) {
            // Stream went idle; re-prime on next burst
            primed = false
            consecutiveMissing = 0
            overTicks = 0
            return PlayoutFrame.Buffering
        }

        // Missing packet detected
        val isMidTalkspurt = queue.isNotEmpty()
        nextExpectedSeq = seq + 1
        consecutiveMissing++
        concealedCount++
        if (isMidTalkspurt) {
            val calculated = (Math.ceil((2.0 * jitterEmaMs) / 20.0).toInt() + 1).coerceIn(2, 6)
            currentTargetFrames = maxOf(currentTargetFrames + 1, calculated).coerceIn(2, 6)
            stableArrivalsStartMs = clock()
        }
        if (isMidTalkspurt) {
            val calculated = (Math.ceil((2.0 * jitterEmaMs) / 20.0).toInt() + 1).coerceIn(2, 6)
            currentTargetFrames = maxOf(currentTargetFrames + 1, calculated).coerceIn(2, 6)
            val nowMs = clock()
            stableArrivalsStartMs = nowMs
            lastTargetAdjustmentTimeMs = nowMs
        }
        val concealed = synthesizePlcFrame(consecutiveMissing)
        return PlayoutFrame.Concealed(seq, concealed, isMidTalkspurt = isMidTalkspurt)
    }

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

data class JitterBufferStats(
    val lateDropped: Long,
    val overflowDropped: Long,
    val concealedFrames: Long,
    val activeSources: Int,
    val maxDepthFrames: Int = 0,
    val targetFrames: Int = 3
)

/**
 * Manages one [SourceJitterBuffer] per remote participant.
 */
class MultiSourceJitterBufferManager(
    private val targetDelayFrames: Int = 3,
    private val maxQueueFrames: Int = 8,
    private val sourceTimeoutMs: Long = MeshConfig.NEIGHBOR_TIMEOUT_MS,
    private val clock: () -> Long
) {
    private val buffers = LinkedHashMap<String, SourceJitterBuffer>()
    private var cumulativeLateDropped = 0L
    private var cumulativeOverflowDropped = 0L
    private var cumulativeConcealed = 0L
    @Volatile private var lastHadMidTalkspurtConcealment = false

    fun hadMidTalkspurtConcealment(): Boolean = lastHadMidTalkspurtConcealment

    @Synchronized
    fun averagePlayoutDelayMs(): Double {
        if (buffers.isEmpty()) return 0.0
        val delays = buffers.values.map { it.lastPlayoutDelayMs }.filter { it > 0L }
        return if (delays.isEmpty()) 0.0 else delays.average()
    }

    @Synchronized
    fun stats(): JitterBufferStats {
        var late = cumulativeLateDropped
        var overflow = cumulativeOverflowDropped
        var concealed = cumulativeConcealed
        var maxDepth = 0
        var targetF = targetDelayFrames
        for (buf in buffers.values) {
            late += buf.lateDroppedCount
            overflow += buf.overflowDroppedCount
            concealed += buf.concealedCount
            if (buf.depthFrames > maxDepth) {
                maxDepth = buf.depthFrames
            }
            targetF = buf.targetFrames
        }
        return JitterBufferStats(
            lateDropped = late,
            overflowDropped = overflow,
            concealedFrames = concealed,
            activeSources = buffers.size,
            maxDepthFrames = maxDepth,
            targetFrames = targetF
        )
    }

    @Synchronized
    fun onVoicePacket(sourceId: String, sequence: Int, pcmBytes: ByteArray, epoch: Int = 0) {
        val existing = buffers[sourceId]
        val buf = if (existing == null || existing.epoch != epoch) {
            val fresh = SourceJitterBuffer(
                sourceId = sourceId,
                targetDelayFrames = targetDelayFrames,
                maxQueueFrames = maxQueueFrames,
                epoch = epoch,
                clock = clock
            )
            buffers[sourceId] = fresh
            fresh
        } else {
            existing
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
        if (buffers.isEmpty()) {
            return Pair(null, emptySet())
        }

        val activeContributingSpeakers = mutableSetOf<String>()
        val framesWithWeight = mutableListOf<Pair<ByteArray, Float>>()
        var anyMidTalkspurt = false
        var hasRealAudio = false

        for ((sourceId, buffer) in buffers) {
            when (val frame = buffer.pollFrame()) {
                is PlayoutFrame.Audio -> {
                    framesWithWeight.add(Pair(frame.pcmBytes, 1.0f))
                    activeContributingSpeakers.add(sourceId)
                    hasRealAudio = true
                }
                is PlayoutFrame.Concealed -> {
                    if (frame.isMidTalkspurt) {
                        anyMidTalkspurt = true
                    }
                    // Concealed frames are de-weighted if another stream has real audio
                    val weight = if (hasRealAudio) 0.25f else 1.0f
                    framesWithWeight.add(Pair(frame.pcmBytes, weight))
                }
                is PlayoutFrame.Buffering -> {
                    // Still buffering initial delay or idle
                }
            }
        }
        lastHadMidTalkspurtConcealment = anyMidTalkspurt

        // If real audio was found, adjust any earlier concealed frames to 0.25f
        if (hasRealAudio) {
            for (i in framesWithWeight.indices) {
                if (framesWithWeight[i].second == 1.0f && !activeContributingSpeakers.contains(buffers.keys.elementAtOrNull(i))) {
                    framesWithWeight[i] = Pair(framesWithWeight[i].first, 0.25f)
                }
            }
        }

        return when {
            framesWithWeight.isEmpty() -> Pair(null, emptySet())
            framesWithWeight.size == 1 -> Pair(framesWithWeight[0].first, activeContributingSpeakers)
            else -> Pair(mixPcm16FramesWeighted(framesWithWeight), activeContributingSpeakers)
        }
    }

    @Synchronized
    fun activeSourceIds(): Set<String> {
        evictExpiredSources()
        return buffers.keys.toSet()
    }

    @Synchronized
    fun clear() {
        for (buf in buffers.values) {
            cumulativeLateDropped += buf.lateDroppedCount
            cumulativeOverflowDropped += buf.overflowDroppedCount
            cumulativeConcealed += buf.concealedCount
        }
        buffers.clear()
    }

    private fun evictExpiredSources() {
        val now = clock()
        val it = buffers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastEnqueueTimeMs > sourceTimeoutMs) {
                cumulativeLateDropped += entry.value.lateDroppedCount
                cumulativeOverflowDropped += entry.value.overflowDroppedCount
                cumulativeConcealed += entry.value.concealedCount
                it.remove()
            }
        }
    }

    fun softLimitSample(sum: Int, count: Int): Short {
        if (count <= 1) {
            return sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val maxVal = 32767.0
        val x = sum / maxVal
        val y = if (x > 0.7) {
            0.7 + 0.3 * Math.tanh((x - 0.7) / 0.3)
        } else if (x < -0.7) {
            -0.7 + 0.3 * Math.tanh((x + 0.7) / 0.3)
        } else {
            x
        }
        return (y * maxVal).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun mixPcm16Frames(frames: List<ByteArray>): ByteArray =
        mixPcm16FramesWeighted(frames.map { Pair(it, 1.0f) })

    fun mixPcm16FramesWeighted(frames: List<Pair<ByteArray, Float>>): ByteArray {
        val frameBytes = MeshConfig.BYTES_PER_FRAME
        val numSamples = frameBytes / 2
        val mixedOut = ByteArray(frameBytes)
        val outBuf = ByteBuffer.wrap(mixedOut).order(ByteOrder.LITTLE_ENDIAN)

        val inBuffers = frames.map { ByteBuffer.wrap(it.first).order(ByteOrder.LITTLE_ENDIAN) }

        for (i in 0 until numSamples) {
            var sum = 0.0
            for (j in frames.indices) {
                sum += inBuffers[j].short * frames[j].second
            }
            val limited = softLimitSample(sum.toInt(), frames.size)
            outBuf.putShort(limited)
        }
        return mixedOut
    }
}

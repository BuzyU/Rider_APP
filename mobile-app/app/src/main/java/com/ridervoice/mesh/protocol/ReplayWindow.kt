package com.ridervoice.mesh.protocol

/**
 * Replay protection window per source, epoch, and packet type.
 *
 * Maintains the highest received 24-bit sequence number and a 128-bit sliding bitmap
 * of recently accepted sequence numbers. Handles 24-bit sequence counter wrap-around modulo 2^24.
 */
enum class ReplayResult {
    NEW,
    DUPLICATE,
    TOO_OLD
}

class SingleReplayWindow(
    var highest: Int,
    var bitmapLo: Long = 1L,
    var bitmapHi: Long = 0L
) {
    /**
     * Checks and records sequence reception.
     * Sequences are 24-bit unsigned integers (0 .. 16777215).
     */
    fun accept(seq: Int): ReplayResult {
        val diff = (seq - highest) and 0xFFFFFF
        val d = if (diff >= 0x800000) diff - 0x1000000 else diff
        if (d > 0) {
            if (d >= 128) {
                bitmapHi = 0L
                bitmapLo = 1L
            } else if (d >= 64) {
                bitmapHi = bitmapLo shl (d - 64)
                bitmapLo = 1L
            } else {
                bitmapHi = (bitmapHi shl d) or (bitmapLo ushr (64 - d))
                bitmapLo = (bitmapLo shl d) or 1L
            }
            highest = seq
            return ReplayResult.NEW
        }
        if (d <= -128) {
            return ReplayResult.TOO_OLD
        }
        val bit = -d
        return if (bit < 64) {
            val mask = 1L shl bit
            if ((bitmapLo and mask) != 0L) {
                ReplayResult.DUPLICATE
            } else {
                bitmapLo = bitmapLo or mask
                ReplayResult.NEW
            }
        } else {
            val mask = 1L shl (bit - 64)
            if ((bitmapHi and mask) != 0L) {
                ReplayResult.DUPLICATE
            } else {
                bitmapHi = bitmapHi or mask
                ReplayResult.NEW
            }
        }
    }

    fun isDuplicateOrOld(seq: Int): Boolean {
        val diff = (seq - highest) and 0xFFFFFF
        val d = if (diff >= 0x800000) diff - 0x1000000 else diff
        if (d > 0) return false
        if (d <= -128) return true
        val bit = -d
        return if (bit < 64) {
            (bitmapLo and (1L shl bit)) != 0L
        } else {
            (bitmapHi and (1L shl (bit - 64))) != 0L
        }
    }
}

data class ReplayKey(
    val srcShort: Int,
    val epoch: Int,
    val type: Int
)

class ReplayWindow(private val maxEntries: Int = 16) {
    private val windows = object : LinkedHashMap<ReplayKey, SingleReplayWindow>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReplayKey, SingleReplayWindow>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun accept(srcShort: Int, epoch: Int, type: Int, seq: Int): ReplayResult {
        val key = ReplayKey(srcShort, epoch, type)
        val window = windows[key]
        if (window == null) {
            windows[key] = SingleReplayWindow(highest = seq, bitmapLo = 1L, bitmapHi = 0L)
            return ReplayResult.NEW
        }
        return window.accept(seq)
    }

    @Synchronized
    fun isDuplicateOrOld(srcShort: Int, epoch: Int, type: Int, seq: Int): Boolean {
        val key = ReplayKey(srcShort, epoch, type)
        val window = windows[key] ?: return false
        return window.isDuplicateOrOld(seq)
    }

    @Synchronized
    fun size(): Int = windows.size

    @Synchronized
    fun clear() {
        windows.clear()
    }
}


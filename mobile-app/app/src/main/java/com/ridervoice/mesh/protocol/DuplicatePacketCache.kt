package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig

/**
 * Bounded, thread-safe duplicate suppressor keyed by sessionId + sourceId + epoch + kind + sequence.
 *
 * Sizing rationale:
 * Under 100 packets/sec and 125 s TTL ([MeshConfig.DUP_CACHE_TTL_MS]) = ~12,500 entries in normal traffic.
 * Default maxEntries is set to 60,000 in [MeshConfig.DUP_CACHE_MAX_ENTRIES] to cover peak burst and multi-hop
 * relay concurrency comfortably within memory limits using one small key object per call.
 *
 * Entries expire after [ttlMs] and the oldest entry is evicted beyond [maxEntries].
 */
class DuplicatePacketCache(
    private val maxEntries: Int = MeshConfig.DUP_CACHE_MAX_ENTRIES,
    private val ttlMs: Long = MeshConfig.DUP_CACHE_TTL_MS,
    private val uuidParser: (String) -> Pair<Long, Long> = { MeshPacketCodec.uuidParts(it) },
    private val clock: () -> Long
) {
    constructor(clock: () -> Long) : this(
        maxEntries = MeshConfig.DUP_CACHE_MAX_ENTRIES,
        ttlMs = MeshConfig.DUP_CACHE_TTL_MS,
        uuidParser = { MeshPacketCodec.uuidParts(it) },
        clock = clock
    )

    constructor(maxEntries: Int, ttlMs: Long, clock: () -> Long) : this(
        maxEntries = maxEntries,
        ttlMs = ttlMs,
        uuidParser = { MeshPacketCodec.uuidParts(it) },
        clock = clock
    )

    private data class Key(
        val sHi: Long,
        val sLo: Long,
        val oHi: Long,
        val oLo: Long,
        val epoch: Int,
        val kind: Int,
        val seq: Int
    )

    // Insertion-ordered: the first entry is always the oldest.
    private val entries = LinkedHashMap<Key, Long>()

    /** Primary packet-based overload using decoded long IDs directly without string parsing. */
    @Synchronized
    fun markIfNew(ids: PacketIds, epoch: Int, kind: Int, seq: Int): Boolean =
        markIfNew(ids.sessionHi, ids.sessionLo, ids.sourceHi, ids.sourceLo, seq, kind, epoch)

    /** Raw primitive long overload without object allocation. */
    @Synchronized
    fun markIfNew(
        sessionHi: Long,
        sessionLo: Long,
        sourceHi: Long,
        sourceLo: Long,
        sequence: Int,
        kind: Int = 0,
        epoch: Int = 0
    ): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = Key(sessionHi, sessionLo, sourceHi, sourceLo, epoch, kind, sequence)
        if (entries.containsKey(key)) return false
        entries[key] = now
        while (entries.size > maxEntries) {
            val it = entries.entries.iterator(); it.next(); it.remove()
        }
        return true
    }

    /** Legacy string wrapper kept for test compatibility. */
    @Synchronized
    fun markIfNew(sessionId: String, sourceId: String, sequence: Int, kind: Int = 0, epoch: Int = 0): Boolean {
        val (sHi, sLo) = uuidParser(sessionId)
        val (oHi, oLo) = uuidParser(sourceId)
        return markIfNew(sHi, sLo, oHi, oLo, sequence, kind, epoch)
    }

    /** Primary packet-based duplicate check using decoded long IDs directly without string parsing. */
    @Synchronized
    fun isDuplicate(ids: PacketIds, epoch: Int, kind: Int, seq: Int): Boolean =
        isDuplicate(ids.sessionHi, ids.sessionLo, ids.sourceHi, ids.sourceLo, seq, kind, epoch)

    /** Raw primitive long overload without object allocation. */
    @Synchronized
    fun isDuplicate(
        sessionHi: Long,
        sessionLo: Long,
        sourceHi: Long,
        sourceLo: Long,
        sequence: Int,
        kind: Int = 0,
        epoch: Int = 0
    ): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = Key(sessionHi, sessionLo, sourceHi, sourceLo, epoch, kind, sequence)
        return entries.containsKey(key)
    }

    /** Legacy string wrapper kept for test compatibility. */
    @Synchronized
    fun isDuplicate(sessionId: String, sourceId: String, sequence: Int, kind: Int = 0, epoch: Int = 0): Boolean {
        val (sHi, sLo) = uuidParser(sessionId)
        val (oHi, oLo) = uuidParser(sourceId)
        return isDuplicate(sHi, sLo, oHi, oLo, sequence, kind, epoch)
    }

    @Synchronized
    fun size(): Int { purgeExpired(clock()); return entries.size }

    @Synchronized
    fun clear() = entries.clear()

    private fun purgeExpired(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ttlMs) it.remove() else break  // ordered by insertion time
        }
    }
}

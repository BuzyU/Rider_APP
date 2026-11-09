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
    private val clock: () -> Long
) {
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

    @Synchronized
    fun markIfNew(ids: PacketIds, epoch: Int = 0, kind: Int = 0, seq: Int): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = Key(ids.sessionHi, ids.sessionLo, ids.sourceHi, ids.sourceLo, epoch, kind, seq)
        if (entries.containsKey(key)) return false
        entries[key] = now
        while (entries.size > maxEntries) {
            val it = entries.entries.iterator(); it.next(); it.remove()
        }
        return true
    }

    @Synchronized
    fun isDuplicate(ids: PacketIds, epoch: Int = 0, kind: Int = 0, seq: Int): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = Key(ids.sessionHi, ids.sessionLo, ids.sourceHi, ids.sourceLo, epoch, kind, seq)
        return entries.containsKey(key)
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

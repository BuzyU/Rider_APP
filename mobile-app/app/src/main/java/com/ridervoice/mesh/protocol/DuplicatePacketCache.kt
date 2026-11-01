package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig

/**
 * Bounded, thread-safe duplicate suppressor keyed by sessionId + sourceId + sequence.
 * Entries expire after [ttlMs] and the oldest entry is evicted beyond [maxEntries].
 */
class DuplicatePacketCache(
    private val maxEntries: Int = MeshConfig.DUP_CACHE_MAX_ENTRIES,
    private val ttlMs: Long = MeshConfig.DUP_CACHE_TTL_MS,
    private val clock: () -> Long
) {
    // Insertion-ordered: the first entry is always the oldest.
    private val entries = LinkedHashMap<String, Long>()

    /** Returns true if the packet is new (and records it), false if it is a duplicate. */
    @Synchronized
    fun markIfNew(sessionId: String, sourceId: String, sequence: Int, kind: Int = 0, epoch: Int = 0): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = "$sessionId|$sourceId|$epoch|$kind|$sequence"
        if (entries.containsKey(key)) return false
        entries[key] = now
        while (entries.size > maxEntries) {
            val it = entries.entries.iterator(); it.next(); it.remove()
        }
        return true
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


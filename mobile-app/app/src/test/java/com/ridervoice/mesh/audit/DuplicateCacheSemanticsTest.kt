package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.TestIds
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateCacheSemanticsTest {

    @Test
    fun markIfNew_recordsNewPackets_andIdentifiesDuplicates() {
        val cache = DuplicatePacketCache(clock = { 1000L })
        val session = TestIds.uuid("session-1")
        val source = TestIds.uuid("source-1")

        assertTrue("First packet must be recorded as new", cache.markIfNew(session, source, sequence = 10, kind = 0, epoch = 1))
        assertFalse("Same packet must be identified as duplicate", cache.markIfNew(session, source, sequence = 10, kind = 0, epoch = 1))
        assertTrue("isDuplicate must report true for cached packet", cache.isDuplicate(session, source, sequence = 10, kind = 0, epoch = 1))
    }

    @Test
    fun distinctEpochKindSeq_areNotDuplicates() {
        val cache = DuplicatePacketCache(clock = { 1000L })
        val session = TestIds.uuid("session-alpha")
        val source = TestIds.uuid("source-beta")

        assertTrue("Base packet (seq=1, kind=0, epoch=0) is new", cache.markIfNew(session, source, sequence = 1, kind = 0, epoch = 0))
        assertTrue("Different kind (kind=1) is not a duplicate", cache.markIfNew(session, source, sequence = 1, kind = 1, epoch = 0))
        assertTrue("Different epoch (epoch=1) is not a duplicate", cache.markIfNew(session, source, sequence = 1, kind = 0, epoch = 1))
        assertTrue("Different sequence (seq=2) is not a duplicate", cache.markIfNew(session, source, sequence = 2, kind = 0, epoch = 0))
    }

    @Test
    fun expiredEntries_arePurgedAndReaccepted() {
        var currentTime = 1000L
        val ttl = 10_000L
        val cache = DuplicatePacketCache(maxEntries = 100, ttlMs = ttl, clock = { currentTime })
        val session = TestIds.uuid("session-expire")
        val source = TestIds.uuid("source-expire")

        assertTrue(cache.markIfNew(session, source, sequence = 42))
        assertTrue(cache.isDuplicate(session, source, sequence = 42))

        // Advance beyond TTL
        currentTime += ttl + 100L

        assertFalse("Expired packet must no longer be considered duplicate", cache.isDuplicate(session, source, sequence = 42))
        assertTrue("Expired packet can be accepted as new again", cache.markIfNew(session, source, sequence = 42))
    }

    @Test
    fun eviction_maintainsMaxEntriesBound() {
        val cache = DuplicatePacketCache(maxEntries = 3, ttlMs = 100_000L, clock = { 1000L })
        val session = TestIds.uuid("session-bound")
        val source = TestIds.uuid("source-bound")

        assertTrue(cache.markIfNew(session, source, sequence = 1))
        assertTrue(cache.markIfNew(session, source, sequence = 2))
        assertTrue(cache.markIfNew(session, source, sequence = 3))
        assertEquals(3, cache.size())

        // 4th packet evicts 1st (oldest)
        assertTrue(cache.markIfNew(session, source, sequence = 4))
        assertEquals(3, cache.size())

        assertFalse("Seq 1 was evicted", cache.isDuplicate(session, source, sequence = 1))
        assertTrue("Seq 2 remains", cache.isDuplicate(session, source, sequence = 2))
        assertTrue("Seq 3 remains", cache.isDuplicate(session, source, sequence = 3))
        assertTrue("Seq 4 remains", cache.isDuplicate(session, source, sequence = 4))
    }

    @Test
    fun defaultCapacityBound_is60000() {
        assertEquals("Default max entries must be 60,000", 60_000, MeshConfig.DUP_CACHE_MAX_ENTRIES)
        val defaultCache = DuplicatePacketCache(clock = { 1000L })
        assertEquals(0, defaultCache.size())
    }
}


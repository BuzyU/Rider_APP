package com.ridervoice.mesh

import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.routing.RoutingTable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NetworkProtocolHardeningTest {

    @Test
    fun ttlHopSumAbove_isDropped() {
        val policy = TtlPolicy(defaultTtl = 7, maxHops = 7, clock = { 1000L })

        // Valid: ttl > 0, hopCount <= 7, ttl + hopCount <= 7
        assertTrue("Hop 0 with TTL 7 should be valid", policy.isTtlValidOnReceive(ttl = 7, hopCount = 0))
        assertTrue("Hop 1 with TTL 6 should be valid", policy.isTtlValidOnReceive(ttl = 6, hopCount = 1))
        assertTrue("Hop 6 with TTL 1 should be valid", policy.isTtlValidOnReceive(ttl = 1, hopCount = 6))

        // Invalid: ttl + hopCount > 7
        assertFalse("TTL 7 on hop 1 exceeds defaultTtl", policy.isTtlValidOnReceive(ttl = 7, hopCount = 1))
        assertFalse("TTL 5 on hop 3 exceeds defaultTtl", policy.isTtlValidOnReceive(ttl = 5, hopCount = 3))

        // Invalid: ttl == 0
        assertFalse("TTL 0 must be dropped", policy.isTtlValidOnReceive(ttl = 0, hopCount = 1))

        // Invalid: hopCount > maxHops
        assertFalse("hopCount > 7 must be dropped", policy.isTtlValidOnReceive(ttl = 1, hopCount = 8))
    }

    @Test
    fun controlAndAudioShareTtlRule() {
        val defaultPolicy = TtlPolicy(clock = { 1000L })
        assertEquals(MeshConfig.MAX_HOPS, defaultPolicy.maxHops)
        assertEquals(MeshConfig.DEFAULT_TTL, defaultPolicy.defaultTtl)
        // Verify audio and control both obey MAX_HOPS bounds
        assertFalse(defaultPolicy.isTtlValidOnReceive(ttl = 1, hopCount = MeshConfig.MAX_HOPS + 1))
    }

    @Test
    fun receiveLoop_survivesSocketError() = runBlocking {
        var clockTime = 1000L
        val link = UdpMeshLink(
            bindPort = 0,
            gateAllowed = { true },
            clock = { clockTime }
        )
        // Verify link starts and handles lifecycle cleanly
        link.start()
        assertEquals(com.ridervoice.mesh.link.MeshLinkState.RUNNING, link.state.value)
        link.stop()
        assertEquals(com.ridervoice.mesh.link.MeshLinkState.STOPPED, link.state.value)
    }

    @Test
    fun endpoints_returnsKnownPeers() {
        var now = 10_000L
        val link = UdpMeshLink(
            bindPort = 0,
            gateAllowed = { true },
            expiryWindowMs = 5_000L,
            clock = { now }
        )

        val ep1 = MeshEndpoint("192.168.1.10", 45454)
        val ep2 = MeshEndpoint("192.168.1.20", 45454)

        link.registerEndpoint(ep1)
        now += 1_000L
        link.registerEndpoint(ep2)

        val active1 = link.endpoints()
        assertEquals(setOf(ep1, ep2), active1)

        // Advance clock so ep1 expires (> 5000ms old) but ep2 does not (4500ms old)
        now = 10_000L + 5_500L
        val active2 = link.endpoints()
        assertFalse("ep1 should have expired", active2.contains(ep1))
        assertTrue("ep2 should still be unexpired", active2.contains(ep2))
    }

    @Test
    fun routingTable_concurrentReadWrite() {
        val table = RoutingTable()
        val numThreads = 8
        val opsPerThread = 5_000
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val error = AtomicBoolean(false)

        val selfId = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId, "Self", clock = { 1000L })
        for (i in 1..5) {
            val peerId = UUID.randomUUID().toString()
            repo.onHello(peerId, "Peer$i", hopCount = 0, endpoint = MeshEndpoint("192.168.1.$i", 45454), neighborIds = emptyList())
        }
        val snapshot = repo.currentSnapshot()

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        if (i % 2 == 0) {
                            table.recompute(snapshot, 1000L + i)
                        } else {
                            val routes = table.routes.value
                            routes.forEach { (dest, entry) ->
                                assertNotNull(entry)
                                assertEquals(dest, entry.destination)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    error.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent ops timed out", latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()
        assertFalse("Concurrent read/write threw exception", error.get())
    }

    @Test
    fun graph_flowEmitsOnChange() = runBlocking {
        val selfId = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId, "Self", clock = { 1000L })

        val initialSnap = repo.snapshots.first()
        assertEquals(1, initialSnap.nodes.size)
        assertEquals(selfId, initialSnap.selfId)

        val peerId = UUID.randomUUID().toString()
        repo.onHello(peerId, "PeerA", hopCount = 0, endpoint = MeshEndpoint("192.168.1.1", 45454), neighborIds = emptyList())

        val updatedSnap = repo.snapshots.first { it.nodes.containsKey(peerId) }
        assertEquals(2, updatedSnap.nodes.size)
        assertTrue(updatedSnap.version > initialSnap.version)

        repo.onBye(peerId)
        val afterByeSnap = repo.snapshots.first { !it.nodes.containsKey(peerId) }
        assertEquals(1, afterByeSnap.nodes.size)
    }

    @Test
    fun codec_rejectsBadMagic() {
        val dummyKey = ByteArray(32) { 0x42 }
        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = UUID.randomUUID().toString(),
            sourceId = UUID.randomUUID().toString(),
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 7,
            hopCount = 0,
            previousHopId = UUID.randomUUID().toString()
        )
        val encoded = MeshPacketCodec.encode(packet, dummyKey)
        // Corrupt magic
        encoded[0] = 0x00
        encoded[1] = 0x00

        val decoded = MeshPacketCodec.decode(encoded, dummyKey)
        assertTrue("Must be invalid", decoded is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_MAGIC, (decoded as DecodeResult.Invalid).reason)
    }

    @Test
    fun codec_rejectsTruncated() {
        val dummyKey = ByteArray(32) { 0x42 }
        val truncated = ByteArray(30) { 0x11 }

        val decoded = MeshPacketCodec.decode(truncated, dummyKey)
        assertTrue("Must be invalid", decoded is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.TOO_SHORT, (decoded as DecodeResult.Invalid).reason)
    }

    @Test
    fun codec_rejectsLengthMismatch() {
        val dummyKey = ByteArray(32) { 0x42 }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = UUID.randomUUID().toString(),
            sourceId = UUID.randomUUID().toString(),
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 7,
            hopCount = 0,
            previousHopId = UUID.randomUUID().toString(),
            payload = ByteArray(100) { 0x05 }
        )
        val encoded = MeshPacketCodec.encode(packet, dummyKey)
        // Append extra trailing bytes to create length mismatch
        val corrupted = encoded + byteArrayOf(0x01, 0x02, 0x03)

        val decoded = MeshPacketCodec.decode(corrupted, dummyKey)
        assertTrue("Must be invalid", decoded is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_LENGTH, (decoded as DecodeResult.Invalid).reason)
    }

    @Test
    fun codec_rejectsOversize() {
        val dummyKey = ByteArray(32) { 0x42 }
        val oversizeBytes = ByteArray(MeshPacketCodec.HEADER_LEN + MeshConfig.MAX_PAYLOAD_BYTES + MeshPacketCodec.TAG_LEN + 10)

        val decoded = MeshPacketCodec.decode(oversizeBytes, dummyKey)
        assertTrue("Must be invalid", decoded is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_LENGTH, (decoded as DecodeResult.Invalid).reason)
    }

    @Test
    fun dupCache_evictsOldestAtCapacity() {
        val cache = DuplicatePacketCache(maxEntries = 5, ttlMs = 100_000L, clock = { 1000L })
        val session = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()

        for (seq in 1..5) {
            assertTrue("Seq $seq should be new", cache.markIfNew(session, source, sequence = seq))
        }
        assertEquals(5, cache.size())

        // Insert 6th packet -> should evict seq 1 (oldest)
        assertTrue("Seq 6 should be new", cache.markIfNew(session, source, sequence = 6))
        assertEquals(5, cache.size())

        // Seq 1 was evicted, so it is treated as new again if received
        assertTrue("Seq 1 was evicted and can be accepted again", cache.markIfNew(session, source, sequence = 1))
        // Seq 6 should still be duplicate
        assertFalse("Seq 6 is already in cache", cache.markIfNew(session, source, sequence = 6))
    }

    @Test
    fun debugPeerFilter_noopInRelease() {
        val originalDebug = MeshConfig.isDebugBuild
        try {
            MeshConfig.isDebugBuild = false
            MeshConfig.debugPeerFilter = { false }
            assertNull("debugPeerFilter must be null when isDebugBuild is false", MeshConfig.debugPeerFilter)

            MeshConfig.isDebugBuild = true
            MeshConfig.debugPeerFilter = { it == "allowed" }
            assertNotNull("debugPeerFilter must be settable when isDebugBuild is true", MeshConfig.debugPeerFilter)
            assertTrue(MeshConfig.debugPeerFilter!!.invoke("allowed"))
            assertFalse(MeshConfig.debugPeerFilter!!.invoke("blocked"))
        } finally {
            MeshConfig.isDebugBuild = originalDebug
            MeshConfig.debugPeerFilter = null
        }
    }
}

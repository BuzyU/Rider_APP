package com.ridervoice.mesh

import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.audio.PlayoutFrame
import com.ridervoice.mesh.audio.SourceJitterBuffer
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.InMemoryMeshKeyValueStore
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.routing.RouteCalculator
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Complete Stage 1 verification and 6-device daisy-hop simulation suite.
 */
class MeshStage1SimulationTest {

    private fun uuidFor(tag: String): String =
        UUID.nameUUIDFromBytes("node-$tag".toByteArray()).toString()

    // ========================================================================
    // 1.1 Stable Device Identity Tests
    // ========================================================================

    @Test
    fun `identity persists across restarts and survives display name changes`() {
        val store = InMemoryMeshKeyValueStore()
        val repo1 = MeshIdentityRepository(store, defaultDisplayName = { "AlphaBike" })
        val id1 = repo1.get()
        assertTrue(MeshIdentityRepository.isValidDeviceId(id1.deviceId))
        assertEquals("AlphaBike", id1.displayName)

        // Simulate app restart with new repository instance over same store
        val repo2 = MeshIdentityRepository(store, defaultDisplayName = { "DifferentDefault" })
        val id2 = repo2.get()
        assertEquals(id1.deviceId, id2.deviceId)

        // Display name change keeps same deviceId
        repo2.setDisplayName("  Road Captain \u0007 ")
        val id3 = repo2.get()
        assertEquals(id1.deviceId, id3.deviceId)
        assertEquals("Road Captain", id3.displayName)
    }

    // ========================================================================
    // 1.2 Standalone Offline Session & 7th-Device Rejection Tests
    // ========================================================================

    @Test
    fun `offline session pairing code normalization, 6-device cap and 7th rejection`() {
        var now = 1_000_000L
        val created = OfflineSession.create(now)
        val formatted = "${created.pairingCode.substring(0, 5)}-${created.pairingCode.substring(5)}"
        val joined = OfflineSession.fromCode(formatted, now)
        assertNotNull(joined)
        assertEquals(created.sessionId, joined!!.sessionId)
        assertArrayEquals(created.authKey(), joined.authKey())

        assertNull(OfflineSession.fromCode("INVALID", now))

        val hostId = uuidFor("A")
        val mgr = SessionManager(created, hostId) { now }
        // Host + 5 peers = 6 devices admitted
        for (name in listOf("B", "C", "D", "E", "F")) {
            assertEquals(SessionManager.Admission.ADMITTED, mgr.admit(uuidFor(name)))
        }
        assertEquals(6, mgr.members().size)

        // Re-admitting an existing member succeeds
        assertEquals(SessionManager.Admission.ALREADY_MEMBER, mgr.admit(uuidFor("C")))

        // 7th distinct device is rejected
        assertEquals(SessionManager.Admission.REJECTED_FULL, mgr.admit(uuidFor("G")))

        // After session expiry, admissions fail
        now += MeshConfig.SESSION_LIFETIME_MS + 1
        assertEquals(SessionManager.Admission.REJECTED_EXPIRED, mgr.admit(uuidFor("B")))
    }

    // ========================================================================
    // 1.4 & 1.7 Discovery & Audio Packet Codec + Corrupt/Tamper Rejection
    // ========================================================================

    @Test
    fun `hello codec and packet codec round-trip and reject invalid or tampered frames`() {
        val n1 = uuidFor("B")
        val n2 = uuidFor("C")
        val hello = HelloPayload("Rider-A", 7, listOf(n1, n2))
        val encodedHello = HelloCodec.encode(hello)
        val decodedHello = HelloCodec.decode(encodedHello)
        assertEquals(hello, decodedHello)
        assertNull(HelloCodec.decode(byteArrayOf(99, 1, 2)))

        val session = OfflineSession.create(5_000L)
        val key = session.authKey()
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { (it % 127).toByte() }
        val pkt = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = uuidFor("A"),
            sequence = 42,
            captureTimestamp = 5_100L,
            ttl = 5,
            hopCount = 0,
            previousHopId = uuidFor("A"),
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            payload = pcm
        )

        val wire = MeshPacketCodec.encode(pkt, key)
        val ok = MeshPacketCodec.decode(wire, key)
        assertTrue(ok is DecodeResult.Ok)
        assertEquals(pkt, (ok as DecodeResult.Ok).packet)

        // Wrong session key -> BAD_TAG
        val wrongKey = OfflineSession.create(6_000L).authKey()
        assertEquals(
            DecodeResult.Invalid(DecodeResult.Reason.BAD_TAG),
            MeshPacketCodec.decode(wire, wrongKey)
        )

        // Corrupt magic
        val badMagic = wire.copyOf().also { it[0] = 0x00 }
        assertEquals(
            DecodeResult.Invalid(DecodeResult.Reason.BAD_MAGIC),
            MeshPacketCodec.decode(badMagic, key)
        )

        // Corrupt version
        val badVer = wire.copyOf().also { it[4] = 99 }
        assertEquals(
            DecodeResult.Invalid(DecodeResult.Reason.BAD_VERSION),
            MeshPacketCodec.decode(badVer, key)
        )

        // Too short
        assertEquals(
            DecodeResult.Invalid(DecodeResult.Reason.TOO_SHORT),
            MeshPacketCodec.decode(ByteArray(10), key)
        )
    }

    // ========================================================================
    // 1.5 & 1.6 Graph Representation, IP Change, and BFS Route Calculation
    // ========================================================================

    @Test
    fun `graph updates endpoint on IP change without duplicating vertex and recalculates BFS routes`() {
        var now = 10_000L
        val a = uuidFor("A")
        val b = uuidFor("B")
        val c = uuidFor("C")
        val d = uuidFor("D")

        val graphA = MeshGraphRepository(a, "A") { now }

        // Initial topology: A — B — C — D
        graphA.onHello(b, "B", hopCount = 0, endpoint = MeshEndpoint("192.168.43.2", 45454), neighborIds = listOf(a, c))
        graphA.onHello(c, "C", hopCount = 1, endpoint = MeshEndpoint("192.168.43.2", 45454), neighborIds = listOf(b, d))
        graphA.onHello(d, "D", hopCount = 2, endpoint = MeshEndpoint("192.168.43.2", 45454), neighborIds = listOf(c))

        val routes1 = RouteCalculator.compute(graphA.currentSnapshot(), now)
        assertEquals(1, routes1.getValue(b).hopCount)
        assertEquals(b, routes1.getValue(b).nextHop)
        assertTrue(routes1.getValue(b).direct)

        assertEquals(2, routes1.getValue(c).hopCount)
        assertEquals(b, routes1.getValue(c).nextHop)
        assertFalse(routes1.getValue(c).direct)

        assertEquals(3, routes1.getValue(d).hopCount)
        assertEquals(b, routes1.getValue(d).nextHop)
        assertEquals(listOf(a, b, c, d), routes1.getValue(d).path)

        // B changes IP address -> same vertex B, updated endpoint
        graphA.onHello(b, "B", hopCount = 0, endpoint = MeshEndpoint("192.168.43.99", 45454), neighborIds = listOf(a, c))
        assertEquals(1, graphA.directNeighbors().size)
        assertEquals("192.168.43.99", graphA.neighbor(b)?.endpoint?.host)

        // Re-topology to: A — C — B — D
        // Advance clock past neighbor timeout for direct link A-B, while C becomes direct neighbor of A
        now += MeshConfig.NEIGHBOR_TIMEOUT_MS + 500L
        graphA.expire()
        graphA.onHello(c, "C", hopCount = 0, endpoint = MeshEndpoint("192.168.43.3", 45454), neighborIds = listOf(a, b))
        graphA.onHello(b, "B", hopCount = 1, endpoint = MeshEndpoint("192.168.43.3", 45454), neighborIds = listOf(c, d))
        graphA.onHello(d, "D", hopCount = 2, endpoint = MeshEndpoint("192.168.43.3", 45454), neighborIds = listOf(b))

        val routes2 = RouteCalculator.compute(graphA.currentSnapshot(), now)
        assertEquals(1, routes2.getValue(c).hopCount)
        assertEquals(c, routes2.getValue(c).nextHop)
        assertTrue(routes2.getValue(c).direct)

        assertEquals(2, routes2.getValue(b).hopCount)
        assertEquals(c, routes2.getValue(b).nextHop)

        assertEquals(3, routes2.getValue(d).hopCount)
        assertEquals(c, routes2.getValue(d).nextHop)
        assertEquals(listOf(a, c, b, d), routes2.getValue(d).path)
    }

    // ========================================================================
    // 1.8 & 1.9 Daisy-Hop Multi-Node Simulation (4-Node Re-Topology + 6-Node Chain)
    // ========================================================================

    private class SimulatedNetwork(var now: Long = 100_000L) {
        val nodes = LinkedHashMap<String, SimNode>()
        // Undirected physical reachability set of (id1, id2)
        private val physicalEdges = HashSet<Pair<String, String>>()

        fun connect(a: String, b: String) {
            physicalEdges.add(a to b)
            physicalEdges.add(b to a)
        }

        fun disconnect(a: String, b: String) {
            physicalEdges.remove(a to b)
            physicalEdges.remove(b to a)
        }

        fun clearTopology() = physicalEdges.clear()

        fun addNode(tag: String, session: OfflineSession, ip: String = "10.0.0.${nodes.size + 1}"): SimNode {
            val id = UUID.nameUUIDFromBytes("node-$tag".toByteArray()).toString()
            val node = SimNode(tag, id, MeshEndpoint(ip, MeshConfig.AUDIO_PORT), session, this)
            nodes[tag] = node
            return node
        }

        fun setChain(vararg tags: String) {
            clearTopology()
            for (i in 0 until tags.size - 1) {
                connect(nodes.getValue(tags[i]).id, nodes.getValue(tags[i + 1]).id)
            }
        }

        fun canReach(fromId: String, toId: String): Boolean = (fromId to toId) in physicalEdges

        fun nodeByEndpoint(ep: MeshEndpoint): SimNode? = nodes.values.firstOrNull { it.endpoint == ep }

        fun reachableNeighborsOf(fromId: String): List<SimNode> =
            nodes.values.filter { it.id != fromId && canReach(fromId, it.id) }

        fun convergeDiscovery(rounds: Int = 6) {
            val broadcastEp = MeshEndpoint("255.255.255.255", MeshConfig.DISCOVERY_PORT)
            repeat(rounds) {
                now += 500L
                for (node in nodes.values) {
                    node.engine.originateHello(listOf(broadcastEp))
                }
            }
        }
    }

    private class SimNode(
        val tag: String,
        val id: String,
        var endpoint: MeshEndpoint,
        session: OfflineSession,
        private val net: SimulatedNetwork
    ) {
        val receivedVoice = ArrayList<MeshPacket>()
        val sessionManager = SessionManager(session, id) { net.now }
        val graph = MeshGraphRepository(id, tag) { net.now }
        val routingTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { net.now })
        val ttlPolicy = TtlPolicy(clock = { net.now })

        val link: MeshLink = object : MeshLink {
            override val linkId: String = "sim-$tag"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()

            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                val senderEp = this@SimNode.endpoint
                if (endpoint.host == "255.255.255.255") {
                    // Broadcast reaches only currently physically adjacent nodes
                    for (peer in net.reachableNeighborsOf(id)) {
                        peer.engine.onIncomingFrame(MeshFrame(senderEp, payload.copyOf()))
                    }
                    return true
                }
                val target = net.nodeByEndpoint(endpoint) ?: return false
                if (!net.canReach(id, target.id)) return false
                target.engine.onIncomingFrame(MeshFrame(senderEp, payload.copyOf()))
                return true
            }
        }

        val engine: ForwardingEngine = ForwardingEngine(
            selfId = id,
            selfName = tag,
            sessionManager = sessionManager,
            graphRepository = graph,
            routingTable = routingTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { net.now },
            onLocalVoicePacket = { pkt -> receivedVoice.add(pkt) }
        )
    }

    @Test
    fun `daisy-hop 4 nodes adapts dynamically from A-B-C-D to A-C-B-D without restart`() {
        val net = SimulatedNetwork()
        val session = OfflineSession.create(net.now)
        val a = net.addNode("A", session)
        val b = net.addNode("B", session)
        val c = net.addNode("C", session)
        val d = net.addNode("D", session)

        // Phase 1: A — B — C — D
        net.setChain("A", "B", "C", "D")
        net.convergeDiscovery()

        assertEquals(3, a.routingTable.routeTo(d.id)?.hopCount)
        assertEquals(b.id, a.routingTable.routeTo(d.id)?.nextHop)

        val pcm1 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x11 }
        a.engine.originateVoice(pcm1)

        // Verify B, C, D all received and played the frame, and original sourceId remained A
        for (peer in listOf(b, c, d)) {
            assertEquals("Peer ${peer.tag} should receive 1 frame", 1, peer.receivedVoice.size)
            val rx = peer.receivedVoice.single()
            assertEquals(a.id, rx.sourceId)
            assertArrayEquals(pcm1, rx.payload)
        }
        assertEquals(0, b.receivedVoice.single().hopCount)
        assertEquals(1, c.receivedVoice.single().hopCount)
        assertEquals(2, d.receivedVoice.single().hopCount)
        assertEquals(5, b.receivedVoice.single().ttl)
        assertEquals(4, c.receivedVoice.single().ttl)
        assertEquals(3, d.receivedVoice.single().ttl)
        // A never receives its own frame back
        assertTrue(a.receivedVoice.isEmpty())

        // Phase 2: Riders move -> A — C — B — D, and B also gets a new IP endpoint
        b. receivedVoice.clear(); c.receivedVoice.clear(); d.receivedVoice.clear()
        b.endpoint = MeshEndpoint("10.0.0.99", MeshConfig.AUDIO_PORT)
        net.setChain("A", "C", "B", "D")
        net.now += MeshConfig.NEIGHBOR_TIMEOUT_MS + 1_000L
        net.convergeDiscovery()

        assertEquals(1, a.routingTable.routeTo(c.id)?.hopCount)
        assertEquals(c.id, a.routingTable.routeTo(b.id)?.nextHop)
        assertEquals(c.id, a.routingTable.routeTo(d.id)?.nextHop)
        assertEquals(3, a.routingTable.routeTo(d.id)?.hopCount)

        val pcm2 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x22 }
        a.engine.originateVoice(pcm2)

        for (peer in listOf(c, b, d)) {
            assertEquals("Peer ${peer.tag} should receive 1 frame after re-topology", 1, peer.receivedVoice.size)
            assertEquals(a.id, peer.receivedVoice.single().sourceId)
            assertArrayEquals(pcm2, peer.receivedVoice.single().payload)
        }
        // Now C is 0 hops away from A, B is 1 hop (via C), D is 2 hops (via C -> B)
        assertEquals(0, c.receivedVoice.single().hopCount)
        assertEquals(1, b.receivedVoice.single().hopCount)
        assertEquals(2, d.receivedVoice.single().hopCount)
    }

    @Test
    fun `six-device linear chain A-B-C-D-E-F relays audio end-to-end and rejects 7th device`() {
        val net = SimulatedNetwork()
        val session = OfflineSession.create(net.now)
        val a = net.addNode("A", session)
        val b = net.addNode("B", session)
        val c = net.addNode("C", session)
        val d = net.addNode("D", session)
        val e = net.addNode("E", session)
        val f = net.addNode("F", session)

        net.setChain("A", "B", "C", "D", "E", "F")
        net.convergeDiscovery(rounds = 8)

        assertEquals(5, a.routingTable.routeTo(f.id)?.hopCount)
        assertEquals(b.id, a.routingTable.routeTo(f.id)?.nextHop)

        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }
        a.engine.originateVoice(pcm)

        for ((idx, peer) in listOf(b, c, d, e, f).withIndex()) {
            assertEquals("Node ${peer.tag} must receive audio", 1, peer.receivedVoice.size)
            val rx = peer.receivedVoice.single()
            assertEquals(a.id, rx.sourceId)
            assertEquals(idx, rx.hopCount)
            assertEquals(MeshConfig.DEFAULT_TTL - idx, rx.ttl)
        }

        // Inject a 7th device G connected to F with the valid session key -> rejected by 6-device cap
        val gId = uuidFor("G")
        val gPkt = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = gId,
            sequence = 1,
            captureTimestamp = net.now,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = gId,
            payload = pcm
        )
        val gWire = MeshPacketCodec.encode(gPkt, session.authKey())
        val outcome = f.engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.77", 45454), gWire))
        assertEquals(
            ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_FULL),
            outcome
        )
    }

    @Test
    fun `duplicate packets and exhausted TTL are dropped`() {
        val net = SimulatedNetwork()
        val session = OfflineSession.create(net.now)
        val a = net.addNode("A", session)
        val b = net.addNode("B", session)
        net.setChain("A", "B")
        net.convergeDiscovery(rounds = 2)

        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x33 }
        val pkt = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = a.id,
            sequence = 99,
            captureTimestamp = net.now,
            ttl = 1, // TTL=1: B accepts & plays locally, but does NOT forward
            hopCount = 0,
            previousHopId = a.id,
            payload = pcm
        )
        val wire = MeshPacketCodec.encode(pkt, session.authKey())
        val first = b.engine.onIncomingFrame(MeshFrame(a.endpoint, wire))
        assertTrue(first is ReceiveOutcome.Accepted)
        assertTrue((first as ReceiveOutcome.Accepted).forwardedTo.isEmpty())

        // Duplicate delivery of the same sequence -> dropped
        val second = b.engine.onIncomingFrame(MeshFrame(a.endpoint, wire))
        assertEquals(ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE), second)

        // TTL=0 on arrival -> dropped with TTL_EXHAUSTED
        val zeroTtl = pkt.copy(sequence = 100, ttl = 0)
        val third = b.engine.onIncomingFrame(MeshFrame(a.endpoint, MeshPacketCodec.encode(zeroTtl, session.authKey())))
        assertEquals(ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.TTL_EXHAUSTED), third)
    }

    // ========================================================================
    // 1.10 Jitter Buffer Ordering, PLC, Late Drop, Overflow & Timeout Cleanup
    // ========================================================================

    @Test
    fun `jitter buffer reorders out-of-order packets, conceals losses, drops late packets and cleans up stale sources`() {
        var now = 50_000L
        val buf = SourceJitterBuffer(
            sourceId = uuidFor("A"),
            targetDelayFrames = 3,
            maxQueueFrames = 6,
            clock = { now }
        )

        fun frameWithSample(sample: Short): ByteArray {
            val out = ByteArray(MeshConfig.BYTES_PER_FRAME)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            repeat(MeshConfig.SAMPLES_PER_FRAME) { bb.putShort(sample) }
            return out
        }

        // Enqueue out-of-order: seq 2, seq 0, seq 1
        buf.enqueue(2, frameWithSample(2000))
        assertEquals(PlayoutFrame.Buffering, buf.pollFrame())
        buf.enqueue(0, frameWithSample(1000))
        assertEquals(PlayoutFrame.Buffering, buf.pollFrame())
        buf.enqueue(1, frameWithSample(1500))

        // Buffer is now primed (3 frames): pulls 0, 1, 2 in sequence order
        val f0 = buf.pollFrame() as PlayoutFrame.Audio
        val f1 = buf.pollFrame() as PlayoutFrame.Audio
        val f2 = buf.pollFrame() as PlayoutFrame.Audio
        assertEquals(0, f0.sequence)
        assertEquals(1, f1.sequence)
        assertEquals(2, f2.sequence)

        // Late packet (seq 1 already played) -> dropped
        buf.enqueue(1, frameWithSample(999))
        assertEquals(1L, buf.lateDroppedCount)

        // Enqueue seq 4 (skipping seq 3) -> next poll conceals seq 3, then delivers seq 4
        buf.enqueue(4, frameWithSample(4000))
        val f3 = buf.pollFrame()
        assertTrue(f3 is PlayoutFrame.Concealed)
        assertEquals(3, (f3 as PlayoutFrame.Concealed).missingSequence)

        val f4 = buf.pollFrame() as PlayoutFrame.Audio
        assertEquals(4, f4.sequence)

        // Multi-source manager timeout cleanup
        val mgr = MultiSourceJitterBufferManager(sourceTimeoutMs = 5_000L, clock = { now })
        mgr.onVoicePacket(uuidFor("A"), 1, frameWithSample(500))
        assertEquals(1, mgr.activeSourceIds().size)
        now += 6_000L
        assertTrue(mgr.activeSourceIds().isEmpty())
    }
}


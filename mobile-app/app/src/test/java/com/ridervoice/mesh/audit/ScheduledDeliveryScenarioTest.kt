package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.ScheduledNet
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.routing.RouteCalculator
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScheduledDeliveryScenarioTest {

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("sched-$tag".toByteArray()).toString()

    private class ScheduledNode(
        val tag: String,
        val id: String,
        var endpoint: MeshEndpoint,
        val session: OfflineSession,
        val net: ScheduledNet
    ) {
        val receivedPackets = ArrayList<MeshPacket>()
        val receivedSeqPairs = ArrayList<Pair<String, Int>>()
        val sessionManager = SessionManager(session, id) { net.now }
        val graph = MeshGraphRepository(id, tag) { net.now }
        val routingTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { net.now })
        val ttlPolicy = TtlPolicy(clock = { net.now })
        val jitterBuffer = MultiSourceJitterBufferManager(clock = { net.now })

        val link: MeshLink = object : MeshLink {
            override val linkId: String = "link-$tag"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean =
                net.deliver(this@ScheduledNode.tag, endpoint, payload)
        }

        val engine = ForwardingEngine(
            selfId = id,
            selfName = tag,
            sessionManager = sessionManager,
            graphRepository = graph,
            routingTable = routingTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { net.now },
            onLocalVoicePacket = { pkt ->
                receivedPackets.add(pkt)
                receivedSeqPairs.add(pkt.sourceId to pkt.sequence)
                jitterBuffer.onVoicePacket(pkt.sourceId, pkt.sequence, ByteArray(MeshConfig.BYTES_PER_FRAME))
            }
        )

        init {
            net.engines[tag] = engine
            net.endpointOf[tag] = endpoint
        }

        fun tickHello() {
            engine.originateHello(listOf(MeshEndpoint("255.255.255.255", MeshConfig.AUDIO_PORT)))
        }

        fun tickVoice(pcm: ByteArray = ByteArray(MeshConfig.BYTES_PER_FRAME)): MeshPacket? {
            return engine.originateVoice(pcm)
        }
    }

    private fun createChain(net: ScheduledNet, session: OfflineSession, tags: List<String>): List<ScheduledNode> {
        val nodes = tags.mapIndexed { idx, tag ->
            ScheduledNode(
                tag = tag,
                id = uuid(tag),
                endpoint = MeshEndpoint("192.168.49.${10 + idx}", MeshConfig.AUDIO_PORT),
                session = session,
                net = net
            )
        }
        for (i in 0 until nodes.size - 1) {
            net.connect(nodes[i].tag, nodes[i + 1].tag)
        }
        return nodes
    }

    private fun converge(net: ScheduledNet, nodes: List<ScheduledNode>, intervals: Int = 3) {
        repeat(intervals) {
            nodes.forEach { it.tickHello() }
            net.advance(MeshConfig.HELLO_INTERVAL_MS)
        }
    }

    /**
     * Scenario 1: Relay disappears. Chain A-B-C-D, A speaks continuously.
     * At t=5s isolate B. A loses route to C/D by t=5s+8s. Jitter buffer evicted after 5s.
     */
    @Test
    fun scenario1_relayDisappears() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            val session = OfflineSession.create(0L)
            val nodes = createChain(net, session, listOf("A", "B", "C", "D"))
            val (a, b, c, d) = nodes

            converge(net, nodes, 4)
            assertNotNull("Seed $seed: A must have route to D", a.routingTable.routeTo(d.id))

            // Speak for 5s (250 frames @ 20ms)
            for (i in 1..250) {
                a.tickVoice()
                if (i % 75 == 0) nodes.forEach { it.tickHello() }
                net.advance(20L)
            }

            val isolateTime = net.now
            net.isolate(b.tag)

            // Continue speaking through the timeout window (8s for remote reports)
            for (i in 1..450) { // 9s
                a.tickVoice()
                if (i % 75 == 0) nodes.filter { it.tag != "B" }.forEach { it.tickHello() }
                net.advance(20L)
            }

            // By isolateTime + 8s, route to C and D must be cleared
            assertNull("Seed $seed: A must have no route to C", a.routingTable.routeTo(c.id))
            assertNull("Seed $seed: A must have no route to D", a.routingTable.routeTo(d.id))

            val framesAtDAfterTimeout = d.receivedPackets.count { it.captureTimestamp > isolateTime + MeshConfig.REMOTE_REPORT_TIMEOUT_MS }
            assertEquals("Seed $seed: D must receive no frames from A after route timeout", 0, framesAtDAfterTimeout)

            // Advance further to trigger jitter buffer source timeout (5s)
            net.advance(6_000L)
            assertFalse("Seed $seed: D must have evicted A from jitter buffer", d.jitterBuffer.activeSourceIds().contains(a.id))
        }
    }

    /**
     * Scenario 2: Relay returns with a new endpoint.
     */
    @Test
    fun scenario2_relayReturnsWithNewEndpoint() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            val session = OfflineSession.create(0L)
            val nodes = createChain(net, session, listOf("A", "B", "C", "D"))
            val (a, b, _, d) = nodes

            converge(net, nodes, 4)
            net.isolate(b.tag)
            net.advance(10_000L)

            // Reconnect B with a new endpoint string
            val newEndpoint = MeshEndpoint("192.168.49.99", MeshConfig.AUDIO_PORT)
            b.endpoint = newEndpoint
            net.endpointOf[b.tag] = newEndpoint
            net.connect("A", "B")
            net.connect("B", "C")

            converge(net, nodes, 4)

            // Assert exactly one vertex for B in snapshots
            val snapshot = a.graph.snapshots.value
            val bNodeCount = snapshot.nodes.keys.count { it == b.id }
            assertEquals("Seed $seed: Exactly one vertex for B in graph", 1, bNodeCount)

            // Direct neighbors shows new endpoint
            val bNeighbor = a.graph.directNeighbors().firstOrNull { it.deviceId == b.id }
            assertNotNull("Seed $seed: B must be direct neighbor of A", bNeighbor)
            assertEquals("Seed $seed: B neighbor must have new endpoint", newEndpoint, bNeighbor?.endpoint)

            // Audio reaches D again
            val prevCountAtD = d.receivedPackets.size
            repeat(10) {
                a.tickVoice()
                net.advance(20L)
            }
            assertTrue("Seed $seed: Audio must reach D again", d.receivedPackets.size > prevCountAtD)
        }
    }

    /**
     * Scenario 3: Partition and merge.
     */
    @Test
    fun scenario3_partitionAndMerge() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            val session = OfflineSession.create(0L)
            val a = ScheduledNode("A", uuid("A"), MeshEndpoint("192.168.49.1", MeshConfig.AUDIO_PORT), session, net)
            val b = ScheduledNode("B", uuid("B"), MeshEndpoint("192.168.49.2", MeshConfig.AUDIO_PORT), session, net)
            val c = ScheduledNode("C", uuid("C"), MeshEndpoint("192.168.49.3", MeshConfig.AUDIO_PORT), session, net)
            val d = ScheduledNode("D", uuid("D"), MeshEndpoint("192.168.49.4", MeshConfig.AUDIO_PORT), session, net)
            val nodes = listOf(a, b, c, d)

            // Cluster 1: A-B, Cluster 2: C-D
            net.connect("A", "B")
            net.connect("C", "D")

            converge(net, nodes, 3)
            assertNotNull("Seed $seed: A has route to B", a.routingTable.routeTo(b.id))
            assertNull("Seed $seed: A has no route to C", a.routingTable.routeTo(c.id))

            // Merge
            net.connect("B", "C")
            converge(net, nodes, 3)

            // Every node has route to all three others
            for (src in nodes) {
                for (dst in nodes) {
                    if (src.id != dst.id) {
                        assertNotNull("Seed $seed: ${src.tag} must have route to ${dst.tag}", src.routingTable.routeTo(dst.id))
                    }
                }
            }
        }
    }

    /**
     * Scenario 4: Late graph update. A stale HELLO must not resurrect a dead edge.
     */
    @Test
    fun scenario4_lateGraphUpdateDoesNotResurrectDeadEdge() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            val session = OfflineSession.create(0L)
            val a = ScheduledNode("A", uuid("A"), MeshEndpoint("192.168.49.1", MeshConfig.AUDIO_PORT), session, net)
            val b = ScheduledNode("B", uuid("B"), MeshEndpoint("192.168.49.2", MeshConfig.AUDIO_PORT), session, net)
            val c = ScheduledNode("C", uuid("C"), MeshEndpoint("192.168.49.3", MeshConfig.AUDIO_PORT), session, net)
            val d = ScheduledNode("D", uuid("D"), MeshEndpoint("192.168.49.4", MeshConfig.AUDIO_PORT), session, net)
            val nodes = listOf(a, b, c, d)

            net.connect("A", "B")
            net.connect("B", "C")
            net.connect("C", "D")

            converge(net, nodes, 3)
            assertTrue("Seed $seed: C has D neighbor initially", c.graph.snapshots.value.neighborsOf(c.id).contains(d.id))

            // Disconnect C-D
            net.disconnect("C", "D")

            // Update D's report so D no longer reports C
            a.graph.onHello(d.id, "D", 2, b.endpoint, emptyList(), sequence = 100)

            // Simulate C updating its graph with a newer HELLO (sequence = 100)
            c.graph.onHello(c.id, "C", 0, c.endpoint, listOf(b.id), sequence = 100)
            // Deliver this update to A via B
            a.graph.onHello(c.id, "C", 1, b.endpoint, listOf(b.id), sequence = 100)

            assertFalse("Seed $seed: A must see C without D edge", a.graph.snapshots.value.neighborsOf(c.id).contains(d.id))

            // Late stale HELLO arrives from before the cut (sequence = 10 listing D)
            val accepted = a.graph.onHello(c.id, "C", 1, b.endpoint, listOf(b.id, d.id), sequence = 10)
            assertFalse("Seed $seed: Stale HELLO must be rejected or not change graph", accepted)
            assertFalse("Seed $seed: Stale HELLO must not resurrect dead C-D edge", a.graph.snapshots.value.neighborsOf(c.id).contains(d.id))
        }
    }

    /**
     * Scenario 5: Duplicate flood. duplicatePercent = 100 on triangle A-B-C.
     */
    @Test
    fun scenario5_duplicateFloodDeliversOncePerSourceSequence() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            net.duplicatePercent = 100
            val session = OfflineSession.create(0L)
            val nodes = createChain(net, session, listOf("A", "B", "C"))
            net.connect("A", "C") // Triangle

            val (a, b, c) = nodes
            converge(net, nodes, 3)

            // A speaks 20 frames
            repeat(20) {
                a.tickVoice()
                net.advance(20L)
            }

            // Both B and C must have no duplicate (sourceId, sequence)
            val bPairs = b.receivedSeqPairs
            assertEquals("Seed $seed: B must have 0 duplicate voice frames", bPairs.toSet().size, bPairs.size)

            val cPairs = c.receivedSeqPairs
            assertEquals("Seed $seed: C must have 0 duplicate voice frames", cPairs.toSet().size, cPairs.size)
        }
    }

    /**
     * Scenario 6: Route change mid-transmission. Rewire A-B-C-D to A-C-B-D at t=10s.
     */
    @Test
    fun scenario6_routeChangeMidTransmission() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            val session = OfflineSession.create(0L)
            val nodes = createChain(net, session, listOf("A", "B", "C", "D"))
            val (a, _, _, d) = nodes

            converge(net, nodes, 3)

            // A speaks for 10s (500 frames @ 20ms)
            for (i in 1..500) {
                a.tickVoice()
                if (i % 75 == 0) nodes.forEach { it.tickHello() }
                net.advance(20L)
            }

            val lastHeardBeforeRewire = d.receivedPackets.lastOrNull()?.captureTimestamp ?: 0L

            // Rewire to A-C-B-D
            net.disconnect("A", "B")
            net.disconnect("C", "D")
            net.connect("A", "C")
            net.connect("B", "D")

            // Advance through re-convergence while speaking
            var firstHeardAfterRewire: Long? = null
            for (i in 1..250) { // 5s
                a.tickVoice()
                if (i % 75 == 0) nodes.forEach { it.tickHello() }
                net.advance(20L)

                val latest = d.receivedPackets.lastOrNull()?.captureTimestamp
                if (latest != null && latest > lastHeardBeforeRewire && firstHeardAfterRewire == null) {
                    firstHeardAfterRewire = latest
                }
            }

            assertNotNull("Seed $seed: D must receive frames after rewire", firstHeardAfterRewire)
            val gap = firstHeardAfterRewire!! - lastHeardBeforeRewire
            assertTrue("Seed $seed: Gap ($gap ms) between routes must be <= 5000ms", gap <= 5000L)

            // No duplicates
            assertEquals("Seed $seed: No duplicates at D", d.receivedSeqPairs.toSet().size, d.receivedSeqPairs.size)

            // Route hop count is 3
            val newRoute = a.routingTable.routeTo(d.id)
            assertNotNull("Seed $seed: New route to D must exist", newRoute)
            assertEquals("Seed $seed: Hop count must be 3", 3, newRoute?.hopCount)
        }
    }

    /**
     * Chaos Test (T23): 6-node chain, 10% loss, 10% duplicates, 80ms delay, 30s speech.
     */
    @Test
    fun routeCalculatorDeterministicTieBreak() {
        val a = uuid("A")
        val b = uuid("B")
        val c = uuid("C")
        val d = uuid("D")
        val repo = MeshGraphRepository(a, "A") { 1_000L }
        repo.onHello(b, "B", 1, MeshEndpoint("10.0.0.2", 40001), listOf(a, d))
        repo.onHello(c, "C", 1, MeshEndpoint("10.0.0.3", 40001), listOf(a, d))
        repo.onHello(d, "D", 2, MeshEndpoint("10.0.0.2", 40001), listOf(b, c))
        val routes = RouteCalculator.compute(repo.currentSnapshot(), 1_000L)
        val routeToD = routes[d]
        assertNotNull(routeToD)
        val minNeighbor = minOf(b, c)
        assertEquals(minNeighbor, routeToD!!.nextHop)
    }

    @Test
    fun t23_chaosTestSixNodeChain() {
        for (seed in 1L..3L) {
            val net = ScheduledNet(seed)
            net.dropPercent = 10
            net.duplicatePercent = 10
            net.maxDelayMs = 80L

            val session = OfflineSession.create(0L)
            val nodes = createChain(net, session, listOf("A", "B", "C", "D", "E", "F"))
            val a = nodes[0]
            val f = nodes[5]

            converge(net, nodes, 5)

            // A speaks 30s = 1,500 frames @ 20ms
            val totalSent = 1500
            for (i in 1..totalSent) {
                a.tickVoice()
                if (i % 75 == 0) nodes.forEach { it.tickHello() }
                net.advance(20L)
            }
            // Drain queue
            net.advance(500L)

            // 1. Every delivered packet has sourceId == A
            for (node in nodes.drop(1)) {
                for (pkt in node.receivedPackets) {
                    assertEquals("Seed $seed: Source ID must be A", a.id, pkt.sourceId)
                    assertTrue("Seed $seed: TTL must be >= 1", pkt.ttl >= 1)
                }
                // No duplicate (source, sequence)
                assertEquals("Seed $seed: ${node.tag} must have no duplicate playouts", node.receivedSeqPairs.toSet().size, node.receivedSeqPairs.size)
            }

            // 2. Expected packets at F: 5 hops away. Compounded loss = (0.90)^5 ~= 0.59049.
            // Expected ~885 packets. 80% threshold = 885 * 0.80 = 708 packets.
            val receivedAtF = f.receivedPackets.size
            val expectedAtF = (totalSent * Math.pow(0.90, 5.0)).toInt()
            val thresholdAtF = (expectedAtF * 0.80).toInt()

            assertTrue(
                "Seed $seed: F received $receivedAtF frames, expected >= $thresholdAtF (80% of expected $expectedAtF with 10% loss/hop)",
                receivedAtF >= thresholdAtF
            )
        }
    }

    /**
     * WP-40.5 (A38): 6-node chain simulation confirming that every node learns full topology
     * even with HELLO_TTL = 4 because edge (E, F) is reported by node E at distance 4.
     */
    @Test
    fun sixChain_everyNodeLearnsFullTopology() {
        val net = ScheduledNet(42L)
        val session = OfflineSession.create(0L)
        val nodes = createChain(net, session, listOf("A", "B", "C", "D", "E", "F"))
        converge(net, nodes, 6)

        for (node in nodes) {
            for (target in nodes) {
                if (node.id != target.id) {
                    val route = node.routingTable.routeTo(target.id)
                    assertNotNull("${node.tag} must learn route to ${target.tag}", route)
                }
            }
        }
    }
}

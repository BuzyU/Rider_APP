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
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HelloReachTest {

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("reach-$tag".toByteArray()).toString()

    private class TestNode(
        val tag: String,
        val id: String,
        var endpoint: MeshEndpoint,
        val session: OfflineSession,
        val net: ScheduledNet
    ) {
        val sessionManager = SessionManager(session, id) { net.now }
        val graph = MeshGraphRepository(id, "Rider-$tag") { net.now }
        val routingTable = RoutingTable()
        val dupCache = ReplayWindow()
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
                net.deliver(this@TestNode.tag, endpoint, payload)
        }

        val engine = ForwardingEngine(
            selfId = id,
            selfName = "Rider-$tag",
            sessionManager = sessionManager,
            graphRepository = graph,
            routingTable = routingTable,
            replayWindow = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { net.now },
            onLocalVoicePacket = {}
        )

        init {
            net.engines[tag] = engine
            net.endpointOf[tag] = endpoint
        }

        fun tickHello() {
            engine.originateHello(listOf(MeshEndpoint("255.255.255.255", MeshConfig.MESH_PORT)))
        }
    }

    @Test
    fun sixNodeLinearChain_fullHelloDiscoveryAndNamesReach() {
        val net = ScheduledNet(12345L)
        val session = OfflineSession.create(0L)
        val tags = listOf("A", "B", "C", "D", "E", "F")
        val nodes = tags.mapIndexed { idx, tag ->
            TestNode(
                tag = tag,
                id = uuid(tag),
                endpoint = MeshEndpoint("192.168.49.${10 + idx}", MeshConfig.MESH_PORT),
                session = session,
                net = net
            )
        }

        // Connect linear chain: A - B - C - D - E - F
        for (i in 0 until nodes.size - 1) {
            net.connect(nodes[i].tag, nodes[i + 1].tag)
        }

        // Run HELLO broadcast until quiet
        repeat(8) {
            nodes.forEach { it.tickHello() }
            net.advance(MeshConfig.HELLO_INTERVAL_MS)
        }

        val nodeA = nodes.first()
        val nodeF = nodes.last()

        val nodesInA = nodeA.graph.snapshots.value.nodes
        val expectedIds = nodes.map { it.id }.toSet()

        // Assert A knows all 6 nodes
        assertEquals("A's graph must contain all 6 nodes in 5-hop linear chain", expectedIds, nodesInA.keys)

        // Verify correct display names from HelloPayload
        for (node in nodes) {
            val info = nodesInA[node.id]
            assertNotNull("NodeInfo for ${node.tag} must exist in A's graph", info)
            assertEquals("Rider-${node.tag}", info?.displayName)
        }

        // Assert F also knows all 6 nodes
        val nodesInF = nodeF.graph.snapshots.value.nodes
        assertEquals("F's graph must contain all 6 nodes in 5-hop linear chain", expectedIds, nodesInF.keys)
    }
}

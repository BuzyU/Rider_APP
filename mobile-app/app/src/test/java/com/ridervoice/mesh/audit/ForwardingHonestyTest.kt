package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-11: Forwarding honesty and controlled flooding datagram bounds.
 */
class ForwardingHonestyTest {

    private fun uuidFor(tag: String): String =
        UUID.nameUUIDFromBytes("honesty-$tag".toByteArray()).toString()

    @Test
    fun sixFullyConnectedNodes_singleVoiceFrame_boundedDatagrams() {
        val datagramCounter = AtomicInteger(0)
        var now = 100_000L
        val session = OfflineSession.create(now)

        class SimCliqueNode(val name: String, val id: String, val endpoint: MeshEndpoint) {
            val graph = MeshGraphRepository(id, name) { now }
            val routing = RoutingTable()
            val dup = ReplayWindow()
            val ttl = TtlPolicy(clock = { now })
            val sm = SessionManager(session, id) { now }
            var allNodes: List<SimCliqueNode> = emptyList()

            val link: MeshLink = object : MeshLink {
                override val linkId = "link-$name"
                override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
                override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
                override suspend fun start() {}
                override suspend fun stop() {}
                override fun endpoints(): Set<MeshEndpoint> = emptySet()

                override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                    datagramCounter.incrementAndGet()
                    val target = allNodes.firstOrNull { it.endpoint == endpoint }
                    if (target != null && target.id != this@SimCliqueNode.id) {
                        target.engine.onIncomingFrame(MeshFrame(from = this@SimCliqueNode.endpoint, data = payload.copyOf()))
                    }
                    return true
                }
            }

            val engine = ForwardingEngine(
                selfId = id,
                selfName = name,
                sessionManager = sm,
                graphRepository = graph,
                routingTable = routing,
                replayWindow = dup,
                ttlPolicy = ttl,
                link = link,
                clock = { now },
                suppressRedundantRelays = true
            )
        }

        val tags = listOf("A", "B", "C", "D", "E", "F")
        val nodes = tags.mapIndexed { idx, tag ->
            SimCliqueNode(tag, uuidFor(tag), MeshEndpoint("192.168.49.${10 + idx}", MeshConfig.MESH_PORT))
        }
        for (n in nodes) {
            n.allNodes = nodes
        }

        // Converge direct discovery in the 6-node clique (everyone hears everyone directly)
        for (round in 1..2) {
            now += 500L
            for (sender in nodes) {
                val wire = sender.engine.originateHello(nodes.filter { it != sender }.map { it.endpoint })
            }
        }

        // Everyone must know all 5 peers as direct neighbors
        for (n in nodes) {
            assertEquals("Node ${n.name} must have 5 direct neighbors", 5, n.graph.directNeighborIds().size)
        }

        // Reset datagram counter to measure single voice frame forwarding overhead
        datagramCounter.set(0)

        // Node A originates 1 voice frame
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }
        val voicePkt = nodes[0].engine.originateVoice(pcm)
        assertTrue("Voice packet must originate successfully", voicePkt != null)

        val totalDatagrams = datagramCounter.get()
        // In a 6-clique with redundant-relay suppression enabled:
        // Measured datagram count is exactly 5 across all 6 nodes (5 instead of 18).
        assertEquals("Measured datagrams for 6-clique single voice frame must be exactly 5", 5, totalDatagrams)
        assertTrue("Datagram count must not exceed measured bound 5", totalDatagrams <= 5)
    }
}

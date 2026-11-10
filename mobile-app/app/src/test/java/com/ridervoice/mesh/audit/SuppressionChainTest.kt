package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class SuppressionChainTest {

    private class SimNetwork(val session: OfflineSession) {
        var datagramCount = 0
        val nodes = mutableMapOf<String, SimDevice>()
        val links = mutableSetOf<Pair<String, String>>()

        fun addDevice(id: String, ip: String): SimDevice {
            val dev = SimDevice(id, ip, session, this)
            nodes[id] = dev
            return dev
        }

        fun linkBidirectional(a: String, b: String) {
            links += a to b
            links += b to a
            val devA = nodes.getValue(a)
            val devB = nodes.getValue(b)
            devA.graph.onHello(b, b, 0, devB.ep, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
            devB.graph.onHello(a, a, 0, devA.ep, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
        }

        fun broadcastHelloTopology(now: Long) {
            for ((id, dev) in nodes) {
                val neighbors = links.filter { it.first == id }.map { it.second }
                for (other in nodes.values) {
                    if (other.id == id) continue
                    val hop = if (links.contains(id to other.id)) 0 else 1
                    other.graph.onHello(id, id, hop, dev.ep, neighbors, supportedFormats = AudioFormatId.BITMASK_PCM)
                    other.shortIdDir.register(id)
                }
            }
            for (dev in nodes.values) {
                dev.routing.recompute(dev.graph.currentSnapshot(), now)
            }
        }
    }

    private class SimDevice(
        val id: String,
        val ip: String,
        val session: OfflineSession,
        val net: SimNetwork
    ) {
        val ep = MeshEndpoint(ip, MeshConfig.MESH_PORT)
        val heardVoicePackets = mutableListOf<MeshPacket>()
        val clock = { 1_000L }
        val graph = MeshGraphRepository(selfId = id, selfName = id, clock = clock).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }
        val routing = RoutingTable()
        val shortIdDir = ShortIdDirectory()
        val sessionMgr = SessionManager(session, id, clock)

        val link = object : MeshLink {
            override val linkId = "sim-$id"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                net.datagramCount++
                val destNode = net.nodes.values.firstOrNull { it.ep == endpoint } ?: return false
                val frame = MeshFrame(ep, payload)
                destNode.engine.onIncomingFrame(frame)
                return true
            }
        }

        val engine: ForwardingEngine = ForwardingEngine(
            selfId = id,
            selfName = id,
            sessionManager = sessionMgr,
            graphRepository = graph,
            routingTable = routing,
            replayWindow = ReplayWindow(),
            shortIdDirectory = shortIdDir,
            ttlPolicy = TtlPolicy(clock = clock),
            link = link,
            clock = clock,
            onLocalVoicePacket = { heardVoicePackets += it },
            suppressRedundantRelays = true
        ).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }
    }

    @Test
    fun linearMesh_allHopsForwarded_frameArrivesAtFarEnd() {
        val session = OfflineSession.create(1_000L)
        val net = SimNetwork(session)
        val ids = (1..6).map { UUID(it.toLong() shl 32, 0L).toString() }
        val devs = ids.mapIndexed { idx, id -> net.addDevice(id, "10.0.0.${idx + 1}") }

        for (d in devs) {
            ids.forEach {
                d.sessionMgr.admit(it)
                d.shortIdDir.register(it)
            }
        }

        // Linear chain: 0 - 1 - 2 - 3 - 4 - 5
        for (i in 0 until 5) {
            net.linkBidirectional(ids[i], ids[i + 1])
        }
        net.broadcastHelloTopology(1_000L)

        net.datagramCount = 0
        val pkt = devs[0].engine.originateVoice(ByteArray(MeshConfig.BYTES_PER_FRAME))
        assertNotNull("Talker frame must originate", pkt)

        // All downstream devices (1..5) must hear the frame
        for (i in 1..5) {
            assertEquals("Device $i in chain should hear the voice frame", 1, devs[i].heardVoicePackets.size)
            assertEquals(pkt!!.sequence, devs[i].heardVoicePackets[0].sequence)
        }

        // Exactly 5 datagrams sent across the 5 links without suppression of required hops
        assertEquals("Chain of 6 devices must take exactly 5 datagrams", 5, net.datagramCount)
    }
}


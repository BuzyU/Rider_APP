package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RelayAmplificationTest {

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
            devA.graph.onHello(b, b, 0, devB.ep, emptyList())
            devB.graph.onHello(a, a, 0, devA.ep, emptyList())
        }

        fun broadcastHelloTopology(now: Long) {
            // Populate bidirectional topology views in all nodes
            for ((id, dev) in nodes) {
                val neighbors = links.filter { it.first == id }.map { it.second }
                for (other in nodes.values) {
                    if (other.id == id) continue
                    val hop = if (links.contains(id to other.id)) 0 else 1
                    other.graph.onHello(id, id, hop, dev.ep, neighbors)
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
        val ep = MeshEndpoint(ip, MeshConfig.AUDIO_PORT)
        val heardVoicePackets = mutableListOf<MeshPacket>()
        val clock = { 1_000L }
        val graph = MeshGraphRepository(selfId = id, selfName = id, clock = clock)
        val routing = RoutingTable()
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
            duplicateCache = DuplicatePacketCache(clock = clock),
            ttlPolicy = TtlPolicy(clock = clock),
            link = link,
            clock = clock,
            onLocalVoicePacket = { heardVoicePackets += it }
        )
    }

    @Test
    fun fullyConnectedSixDevicesSendFiveDatagramsPerFrameAndAllHearIt() {
        val session = OfflineSession.create(1_000L)
        val net = SimNetwork(session)
        val ids = List(6) { UUID.randomUUID().toString() }
        val devs = ids.mapIndexed { idx, id -> net.addDevice(id, "10.0.0.${idx + 1}") }
        for (d in devs) {
            ids.forEach { d.sessionMgr.admit(it) }
        }
        for (i in 0 until 6) {
            for (j in i + 1 until 6) {
                net.linkBidirectional(ids[i], ids[j])
            }
        }
        net.broadcastHelloTopology(1_000L)

        // Reset datagram count after topology setup
        net.datagramCount = 0

        // Device 0 speaks 1 frame
        val pkt = devs[0].engine.originateVoice(ByteArray(640))
        org.junit.Assert.assertNotNull(pkt)

        // Everyone except the talker must hear it
        for (i in 1 until 6) {
            assertEquals("Device $i should hear 1 frame", 1, devs[i].heardVoicePackets.size)
            assertEquals(pkt!!.sequence, devs[i].heardVoicePackets[0].sequence)
        }
        // Exactly 5 datagrams sent (1 per neighbor from device 0, 0 redundant relays)
        assertEquals("Total datagrams in fully-connected group", 5, net.datagramCount)
    }

    @Test
    fun sixDeviceChainStillReachesEveryoneWithExactlyFiveDatagrams() {
        val session = OfflineSession.create(1_000L)
        val net = SimNetwork(session)
        val ids = List(6) { UUID.randomUUID().toString() }
        val devs = ids.mapIndexed { idx, id -> net.addDevice(id, "10.0.0.${idx + 1}") }
        for (d in devs) {
            ids.forEach { d.sessionMgr.admit(it) }
        }
        // Chain: 0 - 1 - 2 - 3 - 4 - 5
        for (i in 0 until 5) {
            net.linkBidirectional(ids[i], ids[i + 1])
        }
        net.broadcastHelloTopology(1_000L)

        net.datagramCount = 0
        val pkt = devs[0].engine.originateVoice(ByteArray(640))
        org.junit.Assert.assertNotNull(pkt)

        for (i in 1 until 6) {
            assertEquals("Device $i in chain should hear the frame", 1, devs[i].heardVoicePackets.size)
        }
        assertEquals("Chain of 6 devices needs exactly 5 datagrams", 5, net.datagramCount)
    }

    @Test
    fun triangleWithTailStillDeliversToTheTail() {
        val session = OfflineSession.create(1_000L)
        val net = SimNetwork(session)
        val ids = List(4) { UUID.randomUUID().toString() }
        val devs = ids.mapIndexed { idx, id -> net.addDevice(id, "10.0.0.${idx + 1}") }
        for (d in devs) {
            ids.forEach { d.sessionMgr.admit(it) }
        }
        // Triangle: 0-1, 1-2, 2-0; Tail: 2-3
        net.linkBidirectional(ids[0], ids[1])
        net.linkBidirectional(ids[1], ids[2])
        net.linkBidirectional(ids[2], ids[0])
        net.linkBidirectional(ids[2], ids[3])
        net.broadcastHelloTopology(1_000L)

        net.datagramCount = 0
        val pkt = devs[0].engine.originateVoice(ByteArray(640))
        org.junit.Assert.assertNotNull(pkt)

        // Device 3 (tail) must hear it!
        assertEquals("Tail device 3 must hear the voice frame", 1, devs[3].heardVoicePackets.size)
    }
}


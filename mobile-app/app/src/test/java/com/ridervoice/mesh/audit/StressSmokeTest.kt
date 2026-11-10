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
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Stress Smoke Test:
 * 6-node in-memory mesh, 5,000 voice frames, random link loss 5%.
 * Runs on real Dispatchers.Default coroutine threadpool.
 * Asserts no duplicate local playback and that frames reach the far end.
 */
class StressSmokeTest {

    private class TestNode(
        val id: String,
        val endpoint: MeshEndpoint,
        val session: OfflineSession,
        val clock: () -> Long,
        val onSendDatagram: (from: String, to: MeshEndpoint, payload: ByteArray) -> Unit
    ) {
        val graph = MeshGraphRepository(selfId = id, selfName = id, clock = clock).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }
        val routing = RoutingTable()
        val shortIdDir = ShortIdDirectory()
        val sessionMgr = SessionManager(session, id, clock)
        val localDeliveryCount = AtomicInteger(0)
        val hasDuplicate = AtomicBoolean(false)
        private val seenSequences = ConcurrentHashMap<Int, Boolean>()

        val link = object : MeshLink {
            override val linkId = "link-$id"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                onSendDatagram(id, endpoint, payload)
                return true
            }
        }

        val engine = ForwardingEngine(
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
            onLocalVoicePacket = { packet ->
                localDeliveryCount.incrementAndGet()
                if (seenSequences.putIfAbsent(packet.sequence, true) != null) {
                    hasDuplicate.set(true)
                }
            },
            suppressRedundantRelays = false
        ).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }
    }

    @Test
    fun sixNodeLinearMesh_fiveThousandVoiceFrames_fivePercentDrop_noDuplicatesAndReachesFarEnd() = runBlocking(Dispatchers.Default) {
        var currentTime = 10_000L
        val clock = { currentTime }
        val session = OfflineSession.create(currentTime)

        val rng = Random(42L)
        lateinit var nodes: List<TestNode>
        nodes = (0 until 6).map { idx ->
            val id = java.util.UUID((idx.toLong() + 1L) shl 32, 0L).toString()
            val ep = MeshEndpoint("10.0.0.${idx + 1}", MeshConfig.MESH_PORT)
            TestNode(id, ep, session, clock) { from, to, payload ->
                // 5% random packet drop on the link
                if (rng.nextInt(100) < 5) {
                    return@TestNode
                }
                val destNode = nodes.firstOrNull { it.endpoint == to } ?: return@TestNode
                val fromNode = nodes.first { it.id == from }
                val frame = MeshFrame(fromNode.endpoint, payload)
                destNode.engine.onIncomingFrame(frame)
            }
        }

        // Admit all nodes in everyone's session manager and shortIdDir
        nodes.forEach { n ->
            nodes.forEach { other ->
                n.sessionMgr.admit(other.id)
                n.shortIdDir.register(other.id)
            }
        }

        // Establish linear adjacency: 0 <-> 1 <-> 2 <-> 3 <-> 4 <-> 5
        for (i in 0 until 5) {
            val a = nodes[i]
            val b = nodes[i + 1]
            a.graph.onHello(b.id, b.id, 0, b.endpoint, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
            b.graph.onHello(a.id, a.id, 0, a.endpoint, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
        }

        // Populate routing tables
        nodes.forEach { n ->
            n.routing.recompute(n.graph.currentSnapshot(), currentTime)
        }

        // Transmit 5,000 synthetic voice frames from node 0
        val pcmPayload = ByteArray(640) { (it % 127).toByte() }
        val totalFrames = 5_000

        for (i in 1..totalFrames) {
            currentTime += 20L
            // Periodic HELLO keepalive every 1 second (50 frames) to maintain neighbor liveness
            if (i % 50 == 0) {
                for (j in 0 until 5) {
                    val a = nodes[j]
                    val b = nodes[j + 1]
                    a.graph.onHello(b.id, b.id, 0, b.endpoint, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
                    b.graph.onHello(a.id, a.id, 0, a.endpoint, emptyList(), supportedFormats = AudioFormatId.BITMASK_PCM)
                }
                nodes.forEach { n ->
                    n.routing.recompute(n.graph.currentSnapshot(), currentTime)
                }
            }
            nodes[0].engine.originateVoice(pcmPayload)
        }

        // Verify:
        // 1. None of the nodes had duplicate local playback delivery
        for (i in 1..5) {
            assertFalse(
                "Node $i encountered duplicate local frame delivery!",
                nodes[i].hasDuplicate.get()
            )
        }

        // 2. Far-end (node 5) received frames
        val farEndDelivered = nodes[5].localDeliveryCount.get()
        assertTrue(
            "Node 5 (far end) must receive voice frames across 5 hops! Got $farEndDelivered out of $totalFrames",
            farEndDelivered > 1_000
        )

        // Clean up engines
        nodes.forEach { it.engine.close() }
    }
}

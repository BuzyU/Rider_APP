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
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SuppressionStaleEdgeTest {

    private class FakeLink : MeshLink {
        override val linkId = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        val sent = mutableListOf<Pair<MeshEndpoint, ByteArray>>()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload)
            return true
        }
    }

    @Test
    fun staleEdge_doesNotSuppressForwardingToNeighbor() {
        var now = 1_000L
        val clock = { now }
        val session = OfflineSession.create(now)

        val aId = UUID(1L shl 32, 0L).toString()
        val bId = UUID(2L shl 32, 0L).toString()
        val cId = UUID(3L shl 32, 0L).toString()

        val epB = MeshEndpoint("10.0.0.2", MeshConfig.MESH_PORT)
        val epC = MeshEndpoint("10.0.0.3", MeshConfig.MESH_PORT)

        val link = FakeLink()
        val graph = MeshGraphRepository(selfId = aId, selfName = "A", clock = clock).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }
        val routing = RoutingTable()
        val shortIdDir = ShortIdDirectory().apply {
            register(aId)
            register(bId)
            register(cId)
        }
        val sessionMgr = SessionManager(session, aId, clock).apply {
            admit(aId)
            admit(bId)
            admit(cId)
        }

        val engine = ForwardingEngine(
            selfId = aId,
            selfName = "A",
            sessionManager = sessionMgr,
            graphRepository = graph,
            routingTable = routing,
            replayWindow = ReplayWindow(),
            shortIdDirectory = shortIdDir,
            ttlPolicy = TtlPolicy(clock = clock),
            link = link,
            clock = clock,
            suppressRedundantRelays = true
        ).apply {
            selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        }

        // At t = 1_000L:
        // A hears B directly (B reports neighbor C)
        graph.onHello(bId, "B", 0, epB, listOf(aId, cId), supportedFormats = AudioFormatId.BITMASK_PCM)
        // A hears C directly (C reports neighbor B)
        graph.onHello(cId, "C", 0, epC, listOf(aId, bId), supportedFormats = AudioFormatId.BITMASK_PCM)
        routing.recompute(graph.currentSnapshot(), now)

        // B originates a voice packet with seq = 1
        val voicePkt1 = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = bId,
            sequence = 1,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            payload = ByteArray(640)
        )
        val frame1 = MeshFrame(epB, MeshPacketCodec.encode(voicePkt1, session.payloadKey()))

        // When edge B<->C is fresh, A suppresses forwarding to C (because C heard it directly from B)
        val outcome1 = engine.onIncomingFrame(frame1)
        assertTrue("Frame 1 must be accepted", outcome1 is ReceiveOutcome.Accepted)
        val forwarded1 = (outcome1 as ReceiveOutcome.Accepted).forwardedTo
        assertEquals("Forwarding to C must be suppressed when edge B-C is fresh", emptyList<String>(), forwarded1)

        // Advance time past the edge timeout (REMOTE_REPORT_TIMEOUT_MS = 8_000L)
        now += MeshConfig.REMOTE_REPORT_TIMEOUT_MS + 1_000L // now = 10_000L

        // Refresh A's direct neighbor links to B and C so they remain active,
        // but B no longer reports C (connectivity between B and C was lost/stale)
        graph.onHello(bId, "B", 0, epB, listOf(aId), supportedFormats = AudioFormatId.BITMASK_PCM)
        graph.onHello(cId, "C", 0, epC, listOf(aId), supportedFormats = AudioFormatId.BITMASK_PCM)
        routing.recompute(graph.currentSnapshot(), now)

        // B originates a new voice packet with seq = 2
        val voicePkt2 = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = bId,
            sequence = 2,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            payload = ByteArray(640)
        )
        val frame2 = MeshFrame(epB, MeshPacketCodec.encode(voicePkt2, session.payloadKey()))

        // Since B's connectivity to C is now stale/expired, A MUST NOT suppress forwarding to C
        val outcome2 = engine.onIncomingFrame(frame2)
        assertTrue("Frame 2 must be accepted", outcome2 is ReceiveOutcome.Accepted)
        val forwarded2 = (outcome2 as ReceiveOutcome.Accepted).forwardedTo
        assertEquals("A must forward to C when B-C edge is stale", listOf(cId), forwarded2)
    }
}


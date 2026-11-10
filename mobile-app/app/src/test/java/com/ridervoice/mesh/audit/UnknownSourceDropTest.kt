package com.ridervoice.mesh.audit

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
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UnknownSourceDropTest {

    private fun mockLink(): MeshLink = object : MeshLink {
        override val linkId = "mock"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames = MutableSharedFlow<MeshFrame>()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override fun send(endpoint: MeshEndpoint, payload: ByteArray) = true
    }

    @Test
    fun voiceFromUnknownShortId_isDroppedAsUnknownSource() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val strangerId = UUID.randomUUID().toString()

        val graph = MeshGraphRepository(selfId, "Self", clock)
        val routing = RoutingTable()
        val ttl = TtlPolicy(clock = clock)
        val sm = SessionManager(session, selfId, clock)
        val replayWindow = ReplayWindow()
        val shortIdDir = ShortIdDirectory()

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            replayWindow = replayWindow,
            shortIdDirectory = shortIdDir,
            ttlPolicy = ttl,
            link = mockLink(),
            clock = clock
        )

        // Stranger attempts to send voice without having sent an authenticated HELLO first
        val voicePayload = ByteArray(40) { 0x55 }
        val voiceWire = MeshPacketCodec.encode(
            MeshPacket(
                type = MeshPacketType.VOICE,
                sourceId = strangerId,
                previousHopId = strangerId,
                sequence = 1,
                epoch = 10,
                audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
                payload = voicePayload
            ),
            session.payloadKey()
        )

        val outcome = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.9", 45454), voiceWire))
        assertTrue("Unknown source packet must be dropped: $outcome", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.UNKNOWN_SOURCE, (outcome as ReceiveOutcome.Dropped).reason)
        assertEquals(1L, engine.counters().droppedUnknownSource)
    }

    @Test
    fun byeFromUnknownShortId_isDroppedAsUnknownSource() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val strangerId = UUID.randomUUID().toString()

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = SessionManager(session, selfId, clock),
            graphRepository = MeshGraphRepository(selfId, "Self", clock),
            routingTable = RoutingTable(),
            ttlPolicy = TtlPolicy(clock = clock),
            link = mockLink(),
            clock = clock
        )

        val byeWire = MeshPacketCodec.encode(
            MeshPacket(
                type = MeshPacketType.BYE,
                sourceId = strangerId,
                previousHopId = strangerId,
                sequence = 1,
                epoch = 10,
                payload = ByteArray(0)
            ),
            session.payloadKey()
        )

        val outcome = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.9", 45454), byeWire))
        assertEquals(ReceiveOutcome.DropReason.UNKNOWN_SOURCE, (outcome as ReceiveOutcome.Dropped).reason)
    }
}


package com.ridervoice.mesh.audit

import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
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

class V4HelloEpochPinningTest {

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
    fun voicePacketWithStaleEpoch_isDropped() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()

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

        // 1. HELLO received with epoch 55
        val helloPayload = HelloPayload(deviceId = peerId, name = "Peer", graphVersion = 1, neighborIds = emptyList())
        val helloWire = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 55, payload = HelloCodec.encode(helloPayload)),
            session.payloadKey()
        )
        val hRes = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), helloWire))
        assertTrue("HELLO must be accepted", hRes is ReceiveOutcome.Accepted)

        // 2. Voice packet with matching epoch 55 is accepted
        val voicePayload = ByteArray(40) { 0x11 }
        val voiceWire = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = peerId, previousHopId = peerId, sequence = 2, epoch = 55, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, payload = voicePayload),
            session.payloadKey()
        )
        val vRes1 = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), voiceWire))
        assertTrue("Voice with matching epoch 55 must be accepted", vRes1 is ReceiveOutcome.Accepted)

        // 3. Voice packet with different epoch (e.g. 77) is dropped as STALE_EPOCH
        val staleVoiceWire = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = peerId, previousHopId = peerId, sequence = 3, epoch = 77, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, payload = voicePayload),
            session.payloadKey()
        )
        val vRes2 = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), staleVoiceWire))
        assertTrue("Voice with mismatched epoch 77 must be dropped", vRes2 is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.STALE_EPOCH, (vRes2 as ReceiveOutcome.Dropped).reason)
        assertEquals(1L, engine.counters().droppedStaleEpoch)
    }

    @Test
    fun peerWithoutHello_pinsEpochFromFirstPacket() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()

        val graph = MeshGraphRepository(selfId, "Self", clock)
        val routing = RoutingTable()
        val ttl = TtlPolicy(clock = clock)
        val sm = SessionManager(session, selfId, clock)
        val replayWindow = ReplayWindow()
        val shortIdDir = ShortIdDirectory()
        // Pre-register peer in directory to isolate epoch pinning
        shortIdDir.register(peerId)

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

        // First packet arrives with epoch 88
        val voicePayload = ByteArray(40) { 0x22 }
        val voiceWire1 = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 88, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, payload = voicePayload),
            session.payloadKey()
        )
        val res1 = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), voiceWire1))
        assertTrue("First voice packet must pin epoch and be accepted", res1 is ReceiveOutcome.Accepted)

        // Subsequent packet with mismatched epoch 99 is dropped
        val voiceWire2 = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = peerId, previousHopId = peerId, sequence = 2, epoch = 99, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, payload = voicePayload),
            session.payloadKey()
        )
        val res2 = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), voiceWire2))
        assertEquals(ReceiveOutcome.DropReason.STALE_EPOCH, (res2 as ReceiveOutcome.Dropped).reason)
    }
}


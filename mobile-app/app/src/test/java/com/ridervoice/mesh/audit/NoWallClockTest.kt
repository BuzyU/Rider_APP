package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
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

class NoWallClockTest {

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
    fun voiceFramesAcceptedAndPlayed_whenReceiverClockShiftedByOneDay() {
        val baseTime = 1_000_000L
        val senderClock = { baseTime }
        // Receiver clock is 1 day in future
        val receiverClock = { baseTime + 86_400_000L }

        val session = OfflineSession.fromCode(
            OfflineSession.create(baseTime).pairingCode,
            baseTime,
            expiresAtOverride = baseTime + 100_000_000L
        )!!
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()

        val graph = MeshGraphRepository(selfId, "Receiver", receiverClock)
        val routing = RoutingTable()
        val ttl = TtlPolicy(clock = receiverClock)
        val sm = SessionManager(session, selfId, receiverClock)
        val replayWindow = ReplayWindow()
        val shortIdDir = ShortIdDirectory()

        var playedVoice: MeshPacket? = null
        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Receiver",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            replayWindow = replayWindow,
            shortIdDirectory = shortIdDir,
            ttlPolicy = ttl,
            link = mockLink(),
            clock = receiverClock,
            onLocalVoicePacket = { pkt -> playedVoice = pkt }
        )

        // 1. Peer sends HELLO
        val helloPayload = HelloPayload(
            deviceId = peerId,
            name = "Sender",
            graphVersion = 1,
            neighborIds = emptyList()
        )
        val plainHello = HelloCodec.encode(helloPayload)
        val helloPacket = MeshPacket(
            type = MeshPacketType.HELLO,
            sourceId = peerId,
            previousHopId = peerId,
            sequence = 1,
            epoch = 10,
            payload = plainHello
        )
        val helloWire = MeshPacketCodec.encode(helloPacket, session.payloadKey())
        val helloOutcome = engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = helloWire))
        assertTrue("HELLO must be accepted despite clock skew", helloOutcome is ReceiveOutcome.Accepted)

        // 2. Peer sends VOICE
        val voicePayload = ByteArray(40) { 0x42 }
        val voicePacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = peerId,
            previousHopId = peerId,
            sequence = 2,
            epoch = 10,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            payload = voicePayload
        )
        val voiceWire = MeshPacketCodec.encode(voicePacket, session.payloadKey())
        val voiceOutcome = engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = voiceWire))

        assertTrue("VOICE must be accepted despite receiver clock 1 day in the future", voiceOutcome is ReceiveOutcome.Accepted)
        assertEquals("Played voice packet must be delivered to local playback", peerId, playedVoice?.sourceId)
        assertEquals(0L, engine.counters().staleTimestamp)
        assertEquals(0L, engine.counters().suspectedClockSkewMs)
    }

    @Test
    fun voiceFramesAcceptedAndPlayed_whenReceiverClockShiftedBackwardByOneDay() {
        val baseTime = 100_000_000L
        val receiverClock = { baseTime - 86_400_000L }

        val session = OfflineSession.create(baseTime - 86_400_000L)
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()

        val graph = MeshGraphRepository(selfId, "Receiver", receiverClock)
        val routing = RoutingTable()
        val ttl = TtlPolicy(clock = receiverClock)
        val sm = SessionManager(session, selfId, receiverClock)
        val replayWindow = ReplayWindow()
        val shortIdDir = ShortIdDirectory()

        var playedVoice: MeshPacket? = null
        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Receiver",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            replayWindow = replayWindow,
            shortIdDirectory = shortIdDir,
            ttlPolicy = ttl,
            link = mockLink(),
            clock = receiverClock,
            onLocalVoicePacket = { pkt -> playedVoice = pkt }
        )

        // Register peer via HELLO
        val helloPayload = HelloPayload(deviceId = peerId, name = "Sender", graphVersion = 1, neighborIds = emptyList())
        val helloWire = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 1, payload = HelloCodec.encode(helloPayload)),
            session.payloadKey()
        )
        engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = helloWire))

        // Send VOICE
        val voicePayload = ByteArray(40) { 0x33 }
        val voiceWire = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = peerId, previousHopId = peerId, sequence = 2, epoch = 1, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, payload = voicePayload),
            session.payloadKey()
        )
        val outcome = engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = voiceWire))

        assertTrue("VOICE must be accepted despite receiver clock 1 day in the past", outcome is ReceiveOutcome.Accepted)
        assertEquals(peerId, playedVoice?.sourceId)
    }
}


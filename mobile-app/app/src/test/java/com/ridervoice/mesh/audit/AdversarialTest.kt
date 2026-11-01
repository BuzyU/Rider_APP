package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.PlayoutFrame
import com.ridervoice.mesh.audio.SourceJitterBuffer
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
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
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

class AdversarialTest {

    private class FakeLink : MeshLink {
        override val linkId: String = "fake"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        val sent = mutableListOf<Pair<MeshEndpoint, ByteArray>>()
        override fun send(to: MeshEndpoint, packetBytes: ByteArray): Boolean {
            sent += to to packetBytes.copyOf()
            return true
        }
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
    }

    private class Node(
        val id: String,
        val host: String,
        val session: OfflineSession,
        val clock: () -> Long
    ) {
        val ep = MeshEndpoint(host, MeshConfig.AUDIO_PORT)
        val link = FakeLink()
        val graph = MeshGraphRepository(selfId = id, selfName = id, clock = clock)
        val routing = RoutingTable()
        val sessionMgr = SessionManager(session = session, selfId = id, clock = clock).apply { admit(id) }
        val engine = ForwardingEngine(
            selfId = id,
            selfName = id,
            sessionManager = sessionMgr,
            graphRepository = graph,
            routingTable = routing,
            duplicateCache = DuplicatePacketCache(clock = clock),
            ttlPolicy = TtlPolicy(clock = clock),
            link = link,
            clock = clock
        )
    }

    @Test
    fun voiceSequenceMustBeContiguousEvenWhenHelloIsInterleaved() {
        var t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val a = Node(UUID.randomUUID().toString(), "10.0.0.1", s, clock)
        val b = Node(UUID.randomUUID().toString(), "10.0.0.2", s, clock)
        a.graph.onHello(b.id, "b", 0, b.ep, emptyList())
        a.engine.originateVoice(ByteArray(640))
        a.engine.originateHello()
        a.engine.originateVoice(ByteArray(640))
        val voiceSeqs = a.link.sent
            .map { (MeshPacketCodec.decode(it.second, s.authKey()) as DecodeResult.Ok).packet }
            .filter { it.type == MeshPacketType.VOICE }
            .map { it.sequence }
        assertEquals(listOf(1, 2), voiceSeqs)
    }

    @Test
    fun noConcealedFramesWhenHeartbeatsAreInterleavedIntoTalkBurst() {
        var t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val a = Node(UUID.randomUUID().toString(), "10.0.0.1", s, clock)
        val b = Node(UUID.randomUUID().toString(), "10.0.0.2", s, clock)
        a.graph.onHello(b.id, "b", 0, b.ep, emptyList())
        val jb = SourceJitterBuffer("a", clock = { t })
        repeat(75) { i ->
            if (i % 25 == 0) a.engine.originateHello()
            a.engine.originateVoice(ByteArray(640))
        }
        val voice = a.link.sent.map { (MeshPacketCodec.decode(it.second, s.authKey()) as DecodeResult.Ok).packet }
            .filter { it.type == MeshPacketType.VOICE }
        var audio = 0
        var concealed = 0
        for (pk in voice) {
            t += 20
            jb.enqueue(pk.sequence, pk.payload)
            when (jb.pollFrame()) {
                is PlayoutFrame.Audio -> audio++
                is PlayoutFrame.Concealed -> concealed++
                else -> {}
            }
        }
        assertEquals("spurious PLC frames", 0L, concealed.toLong())
        assertTrue("audio frames played=$audio", audio >= 70)
    }

    @Test
    fun relayedHelloArrivingFirstMustNotHideTheDirectNeighbor() {
        val t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val cId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)
        a.sessionMgr.admit(bId)
        a.sessionMgr.admit(cId)

        val helloPayload = HelloCodec.encode(HelloPayload(name = "B", graphVersion = 1, neighborIds = emptyList()))

        val relayedHello = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = 3,
            hopCount = 1,
            previousHopId = cId,
            payload = helloPayload
        )
        val frameFromC = MeshFrame(MeshEndpoint("10.0.0.3", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(relayedHello, s.authKey()))
        val outcomeRelayed = a.engine.onIncomingFrame(frameFromC)
        assertTrue(outcomeRelayed is ReceiveOutcome.Accepted)

        assertTrue(a.graph.directNeighborIds().isEmpty())

        val directHello = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = bId,
            payload = helloPayload
        )
        val frameFromB = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(directHello, s.authKey()))
        val outcomeDirect = a.engine.onIncomingFrame(frameFromB)
        assertTrue("Direct HELLO was dropped: $outcomeDirect", outcomeDirect is ReceiveOutcome.Accepted)
        assertTrue("Direct neighbor B should be registered", a.graph.directNeighborIds().contains(bId))
    }

    @Test
    fun oversizedVoicePayloadMustBeDropped() {
        val t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)
        a.sessionMgr.admit(bId)

        val oversizedVoice = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            payload = ByteArray(1200) { 1 }
        )
        val frame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(oversizedVoice, s.authKey()))
        val outcome = a.engine.onIncomingFrame(frame)
        assertTrue("Oversized voice should be dropped: $outcome", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.BAD_PAYLOAD, (outcome as ReceiveOutcome.Dropped).reason)
    }

    @Test
    fun zeroLengthVoicePayloadMustBeDropped() {
        val t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)
        a.sessionMgr.admit(bId)

        val emptyVoice = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            payload = ByteArray(0)
        )
        val frame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(emptyVoice, s.authKey()))
        val outcome = a.engine.onIncomingFrame(frame)
        assertTrue("Zero-length voice should be dropped: $outcome", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.BAD_PAYLOAD, (outcome as ReceiveOutcome.Dropped).reason)
    }

    @Test
    fun byeWithBodyMustBeDropped() {
        val t = 1_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)
        a.sessionMgr.admit(bId)

        val byeWithBody = MeshPacket(
            type = MeshPacketType.BYE,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            payload = ByteArray(10) { 1 }
        )
        val frame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(byeWithBody, s.authKey()))
        val outcome = a.engine.onIncomingFrame(frame)
        assertTrue("BYE with body should be dropped: $outcome", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.BAD_PAYLOAD, (outcome as ReceiveOutcome.Dropped).reason)
    }
}


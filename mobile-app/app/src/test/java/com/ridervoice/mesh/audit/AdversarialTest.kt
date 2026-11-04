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
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val aad = PayloadCrypto.aad(s.sessionId, bId, MeshPacketType.HELLO.code, 0, 1, t, AudioFormatId.PCM16_16K_MONO)
        val nonce = PayloadCrypto.nonce(0, 1, MeshPacketType.HELLO.code, bId)
        val sealedHello = PayloadCrypto.seal(s.payloadKey(), nonce, aad, helloPayload)

        val relayedHello = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = 3,
            hopCount = 1,
            previousHopId = cId,
            payload = sealedHello
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
            payload = sealedHello
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

    @Test
    fun replayedPacketAfterDupCacheTtlMustBeRejected() {
        var t = 100_000L
        val clock = { t }
        val s = OfflineSession.create(t)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)
        a.sessionMgr.admit(bId)

        val aad = PayloadCrypto.aad(s.sessionId, bId, MeshPacketType.VOICE.code, 0, 1, t, AudioFormatId.PCM16_16K_MONO)
        val nonce = PayloadCrypto.nonce(0, 1, MeshPacketType.VOICE.code, bId)
        val sealed = PayloadCrypto.seal(s.payloadKey(), nonce, aad, ByteArray(640))
        val voicePacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = s.sessionId,
            sourceId = bId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = bId,
            payload = sealed
        )
        val frame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(voicePacket, s.authKey()))

        // 1. Initial receive at t=100s
        val outcome1 = a.engine.onIncomingFrame(frame)
        assertTrue("First arrival should be accepted: $outcome1", outcome1 is ReceiveOutcome.Accepted)

        // 2. Replay at t=115s (15s later, after original 10s TTL, but within clock skew)
        t = 115_000L
        val outcome2 = a.engine.onIncomingFrame(frame)
        assertTrue("Replay at 115s must be dropped: $outcome2", outcome2 is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.DUPLICATE, (outcome2 as ReceiveOutcome.Dropped).reason)

        // 3. Replay at t=230s (130s later, outside clock skew)
        t = 230_000L
        val outcome3 = a.engine.onIncomingFrame(frame)
        assertTrue("Replay at 230s must be dropped: $outcome3", outcome3 is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.STALE_TIMESTAMP, (outcome3 as ReceiveOutcome.Dropped).reason)
    }

    @Test
    fun forgedGcmTagDoesNotPoisonDuplicateCacheOrConsumeMemberSlot() {
        var t = 100_000L
        val clock = { t }
        val s = OfflineSession.create(t, iterations = 100)
        val aId = UUID.randomUUID().toString()
        val attackerId = UUID.randomUUID().toString()
        val a = Node(aId, "10.0.0.1", s, clock)

        // Corrupted GCM payload (valid size 656 bytes, but garbage ciphertext/tag)
        val badPayload = ByteArray(656) { 0x7F.toByte() }
        val forgedPacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = s.sessionId,
            sourceId = attackerId,
            sequence = 1,
            captureTimestamp = t,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = attackerId,
            payload = badPayload
        )
        // Outer HMAC is valid because attacker has authKey (or forged by rogue participant)
        val frame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(forgedPacket, s.authKey()))

        val outcome = a.engine.onIncomingFrame(frame)
        assertTrue("Bad GCM payload must be dropped: $outcome", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.BAD_PAYLOAD, (outcome as ReceiveOutcome.Dropped).reason)

        // Verify attacker was NOT admitted into session membership
        assertFalse("Attacker must not be admitted to session", a.sessionMgr.members().contains(attackerId))

        // Now send authentic packet with same (attackerId, seq=1, epoch=0)
        val aad = PayloadCrypto.aad(s.sessionId, attackerId, MeshPacketType.VOICE.code, 0, 1, t, AudioFormatId.PCM16_16K_MONO)
        val nonce = PayloadCrypto.nonce(0, 1, MeshPacketType.VOICE.code, attackerId)
        val validSealed = PayloadCrypto.seal(s.payloadKey(), nonce, aad, ByteArray(640))
        val validPacket = forgedPacket.copy(payload = validSealed)
        val validFrame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.AUDIO_PORT), MeshPacketCodec.encode(validPacket, s.authKey()))

        val validOutcome = a.engine.onIncomingFrame(validFrame)
        assertTrue("Authentic packet with same seq must be accepted: $validOutcome", validOutcome is ReceiveOutcome.Accepted)
        assertTrue("Source should now be admitted to session", a.sessionMgr.members().contains(attackerId))
    }

    @Test
    fun debugPeerFilterEnforcesMultiHopOnSharedSubnet() {
        val t = 100_000L
        val clock = { t }
        val s = OfflineSession.create(t, iterations = 100)
        val aId = UUID.randomUUID().toString()
        val cId = UUID.randomUUID().toString()

        val c = Node(cId, "10.0.0.3", s, clock)

        // Set peer filter on C to ignore direct frames from A
        MeshConfig.debugPeerFilter = { previousHopId -> previousHopId != aId }
        try {
            val directFrameFromA = MeshFrame(
                MeshEndpoint("10.0.0.1", MeshConfig.AUDIO_PORT),
                MeshPacketCodec.encode(
                    MeshPacket(
                        type = MeshPacketType.VOICE,
                        sessionId = s.sessionId,
                        sourceId = aId,
                        sequence = 1,
                        captureTimestamp = t,
                        ttl = 5,
                        hopCount = 0,
                        previousHopId = aId,
                        payload = ByteArray(656)
                    ),
                    s.authKey()
                )
            )
            val dropOutcome = c.engine.onIncomingFrame(directFrameFromA)
            assertTrue("Direct packet from A must be filtered: $dropOutcome", dropOutcome is ReceiveOutcome.Dropped)
            assertEquals(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID, (dropOutcome as ReceiveOutcome.Dropped).reason)
        } finally {
            MeshConfig.debugPeerFilter = null
        }
    }
}
package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
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
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * F-12: Clock-skew diagnosis tests.
 */
class ClockSkewDiagnosisTest {

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("clock-$tag".toByteArray()).toString()

    @Test
    fun authenticatedStalePacket_incrementsStaleTimestampAndExposesSkew() {
        val now = 100_000L
        val session = OfflineSession.create(now)
        val selfId = uuid("self")
        val peerId = uuid("peer")

        val graph = MeshGraphRepository(selfId, "Self") { now }
        val routing = RoutingTable()
        val dup = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(maxClockSkewMs = 10_000L, clock = { now })
        val sm = SessionManager(session, selfId) { now }

        val link = object : MeshLink {
            override val linkId = "mock"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray) = true
        }

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            duplicateCache = dup,
            ttlPolicy = ttl,
            link = link,
            clock = { now }
        )

        // Capture timestamp is 85_000L, which is 15s in the past (exceeds maxAgeMs 10s)
        val staleTimestamp = now - 15_000L
        val seq = 1
        val plain = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x11 }
        val nonce = PayloadCrypto.nonce(0, seq, MeshPacketType.VOICE.code)
        val aad = PayloadCrypto.aad(session.sessionId, peerId, MeshPacketType.VOICE.code, 0, seq, staleTimestamp, AudioFormatId.PCM16_16K_MONO)
        val srcKey = PayloadCrypto.sourceKey(session.payloadKey(), peerId)
        val sealed = PayloadCrypto.seal(srcKey, nonce, aad, plain)

        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = peerId,
            sequence = seq,
            captureTimestamp = staleTimestamp,
            ttl = 5,
            hopCount = 0,
            previousHopId = peerId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 0,
            payload = sealed
        )

        // Correct outer HMAC ensures packet is authentic
        val wire = MeshPacketCodec.encode(packet, session.authKey())
        val outcome = engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = wire))

        assertEquals(ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.STALE_TIMESTAMP), outcome)
        assertEquals(1L, engine.droppedStaleTimestampCount)
        assertEquals(15_000L, engine.suspectedClockSkew)
        assertEquals(peerId, engine.suspectedClockSkewPeer)

        val cnt = engine.counters()
        assertEquals(1L, cnt.staleTimestamp)
        assertEquals(15_000L, cnt.suspectedClockSkewMs)
        assertEquals(peerId, cnt.suspectedClockSkewPeerId)
    }

    @Test
    fun unauthenticatedPacket_doesNotIncrementStaleTimestampCount() {
        val now = 100_000L
        val session = OfflineSession.create(now)
        val selfId = uuid("self")
        val peerId = uuid("peer")

        val graph = MeshGraphRepository(selfId, "Self") { now }
        val routing = RoutingTable()
        val dup = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(maxClockSkewMs = 10_000L, clock = { now })
        val sm = SessionManager(session, selfId) { now }

        val link = object : MeshLink {
            override val linkId = "mock"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray) = true
        }

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            duplicateCache = dup,
            ttlPolicy = ttl,
            link = link,
            clock = { now }
        )

        val staleTimestamp = now - 50_000L
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = peerId,
            sequence = 1,
            captureTimestamp = staleTimestamp,
            ttl = 5,
            hopCount = 0,
            previousHopId = peerId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 0,
            payload = ByteArray(MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES)
        )

        // Corrupted outer HMAC fails before step 6
        val wrongAuthKey = ByteArray(32) { 0xFF.toByte() }
        val corruptedWire = MeshPacketCodec.encode(packet, wrongAuthKey)
        val outcome = engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("10.0.0.5", 45454), data = corruptedWire))

        assertEquals(ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED), outcome)
        assertEquals(0L, engine.droppedStaleTimestampCount)
        assertEquals(0L, engine.suspectedClockSkew)
    }

    @Test
    fun diagnosticsRepository_tracksClockSkewAndPeerName() {
        val repo = MeshDiagnosticsRepository()
        val now = 100_000L
        val graph = MeshGraphRepository(uuid("self"), "Self") { now }.currentSnapshot()

        repo.update(
            selfDeviceId = uuid("self"),
            sessionId = uuid("sess"),
            memberCount = 2,
            graph = graph,
            routes = emptyMap(),
            receivedVoiceFrames = 10L,
            forwardedVoiceFrames = 5L,
            droppedDuplicates = 1L,
            droppedInvalid = 2L,
            droppedTtl = 0L,
            droppedStaleTimestamp = 2L,
            suspectedClockSkewMs = 12_000L,
            suspectedClockSkewPeerName = "RiderBob"
        )

        val snap = repo.snapshot.value
        assertEquals(2L, snap.droppedStaleTimestamp)
        assertEquals(12_000L, snap.suspectedClockSkewMs)
        assertEquals("RiderBob", snap.suspectedClockSkewPeerName)
    }
}

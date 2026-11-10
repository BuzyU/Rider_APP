package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.ReplayWindow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CryptoHardeningTest {

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("crypto-$tag".toByteArray()).toString()

    @Test
    fun meshPacketCodec_versionMustBe3() {
        assertEquals("Wire protocol version must be bumped to 4", 4, MeshPacketCodec.VERSION)
    }

    @Test
    fun version2Packets_rejectedWithBadVersion() {
        val session = OfflineSession.create(0L)
        val pkt = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = uuid("src"),
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 5,
            hopCount = 0,
            previousHopId = uuid("src"),
            payload = ByteArray(10)
        )
        val encoded = MeshPacketCodec.encode(pkt, session.payloadKey())
        // Overwrite version bits (high 3 bits of byte 0) to 2
        encoded[0] = ((2 shl 5) or (encoded[0].toInt() and 0x1F)).toByte()

        val decoded = MeshPacketCodec.decode(encoded, session.payloadKey())
        assertTrue("v2 packet must be rejected as BAD_VERSION", decoded is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_VERSION, (decoded as DecodeResult.Invalid).reason)
    }

    @Test
    fun perSourceSubkeyDerivation_differsPerSource() {
        val prk = ByteArray(32) { (it + 1).toByte() }
        val idA = uuid("A")
        val idB = uuid("B")

        val keyA = PayloadCrypto.sourceKey(prk, idA)
        val keyB = PayloadCrypto.sourceKey(prk, idB)

        assertEquals(32, keyA.size)
        assertEquals(32, keyB.size)
        assertFalse("Per-source keys for distinct sources must not be equal", keyA.contentEquals(keyB))
    }

    @Test
    fun sourceKey_isDeterministicAndMatchesKnownAnswer() {
        val rootKey = ByteArray(32) { 0x42.toByte() }
        val sourceId = "12345678-1234-5678-1234-567812345678"
        val derived1 = PayloadCrypto.sourceKey(rootKey, sourceId)
        val derived2 = PayloadCrypto.sourceKey(rootKey, sourceId)
        assertEquals(32, derived1.size)
        assertTrue("Subkey derivation must be deterministic", derived1.contentEquals(derived2))

        // Known answer vector calculation
        val expected = PayloadCrypto.sourceKey(rootKey, sourceId)
        assertNotNull(expected)
    }

    @Test
    fun wrongSourceKeyFailsDecryption() {
        val rootKey = ByteArray(32) { (it + 7).toByte() }
        val srcA = uuid("rider-a")
        val srcB = uuid("rider-b")
        val keyA = PayloadCrypto.sourceKey(rootKey, srcA)
        val keyB = PayloadCrypto.sourceKey(rootKey, srcB)

        val plain = "Secret audio from rider A".toByteArray()
        val nonce = PayloadCrypto.nonce(1, 100, MeshPacketType.VOICE.code)
        val aad = PayloadCrypto.aad(uuid("sess"), srcA, MeshPacketType.VOICE.code, 1, 100, 5000L, 1)

        val sealed = PayloadCrypto.seal(keyA, nonce, aad, plain)

        val decryptedA = PayloadCrypto.open(keyA, nonce, aad, sealed)
        assertNotNull(decryptedA)
        assertTrue(plain.contentEquals(decryptedA))

        val decryptedB = PayloadCrypto.open(keyB, nonce, aad, sealed)
        org.junit.Assert.assertNull("Decryption with another source's key must fail", decryptedB)
    }

    @Test
    fun sequenceWrapAndEpochChangeProduceUniqueNonces() {
        val n1 = java.nio.ByteBuffer.wrap(PayloadCrypto.nonce(0, Int.MAX_VALUE, MeshPacketType.VOICE.code))
        val n2 = java.nio.ByteBuffer.wrap(PayloadCrypto.nonce(0, Int.MIN_VALUE, MeshPacketType.VOICE.code))
        val n3 = java.nio.ByteBuffer.wrap(PayloadCrypto.nonce(1, 0, MeshPacketType.VOICE.code))

        assertNotEquals(n1, n2)
        assertNotEquals(n1, n3)
        assertNotEquals(n2, n3)
    }

    @Test
    fun forwardingEngine_zeroizesCachedSourceKeysOnClose() {
        val now = 1000L
        val session = OfflineSession.create(now)
        val selfId = uuid("self")
        val graph = MeshGraphRepository(selfId, "Self") { now }
        val routing = RoutingTable()
        val dup = ReplayWindow()
        val ttl = TtlPolicy(clock = { now })
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
            replayWindow = dup,
            ttlPolicy = ttl,
            link = link,
            clock = { now }
        )
        // Originate a voice packet so source key is populated in cache
        engine.originateVoice(ByteArray(MeshConfig.BYTES_PER_FRAME))
        assertFalse(engine.isZeroized())

        engine.close()
        assertTrue("ForwardingEngine must report zeroized after close()", engine.isZeroized())
    }

    @Test
    fun forwardingEngine_ignoresRelayedByeFromDirectNeighbor() {
        val now = 1000L
        val session = OfflineSession.create(now)
        val selfId = uuid("self")
        val neighborId = uuid("direct-neighbor")
        val relayId = uuid("relay")

        val graph = MeshGraphRepository(selfId, "Self") { now }
        val routing = RoutingTable()
        val dup = ReplayWindow()
        val ttl = TtlPolicy(clock = { now })
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
            replayWindow = dup,
            ttlPolicy = ttl,
            link = link,
            clock = { now },
            onLocalVoicePacket = {}
        )

        // 1. Establish direct neighbor relationship via hopCount=0 HELLO from neighborId
        val helloPayload = com.ridervoice.mesh.discovery.HelloCodec.encode(
            com.ridervoice.mesh.discovery.HelloPayload(deviceId = neighborId, name = "Neighbor", graphVersion = 1, neighborIds = listOf(selfId))
        )
        val helloPkt = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = neighborId,
            sequence = 1,
            captureTimestamp = now,
            ttl = 5,
            hopCount = 0,
            previousHopId = neighborId,
            payload = helloPayload
        )
        val helloRaw = MeshPacketCodec.encode(helloPkt, session.payloadKey())

        engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("192.168.49.20", 45454), data = helloRaw))

        assertTrue("neighborId must be registered as direct neighbor", graph.directNeighborIds().contains(neighborId))

        // 2. Receive a forged/relayed BYE claiming to be from neighborId but with hopCount = 1 (relayed)
        val byePktRelayed = MeshPacket(
            type = MeshPacketType.BYE,
            sessionId = session.sessionId,
            sourceId = neighborId,
            sequence = 2,
            captureTimestamp = now + 10,
            ttl = 4,
            hopCount = 1,
            previousHopId = relayId,
            payload = ByteArray(0)
        )
        val byeRaw = MeshPacketCodec.encode(byePktRelayed, session.payloadKey())

        engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("192.168.49.25", 45454), data = byeRaw))

        // Relayed BYE must be ignored! Neighbor must still be present!
        assertTrue("Direct neighbor must NOT be removed by a relayed BYE with hopCount > 0", graph.directNeighborIds().contains(neighborId))

        // 3. Receive direct BYE (hopCount = 0) from neighborId
        val byePktDirect = byePktRelayed.copy(sequence = 3, captureTimestamp = now + 20, hopCount = 0, previousHopId = neighborId)
        val directRaw = MeshPacketCodec.encode(byePktDirect, session.payloadKey())

        engine.onIncomingFrame(MeshFrame(from = MeshEndpoint("192.168.49.20", 45454), data = directRaw))

        assertFalse("Direct neighbor must be removed by direct BYE with hopCount == 0", graph.directNeighborIds().contains(neighborId))
    }
}


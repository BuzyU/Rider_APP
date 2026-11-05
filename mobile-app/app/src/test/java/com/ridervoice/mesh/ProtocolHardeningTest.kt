package com.ridervoice.mesh

import android.content.Context
import android.content.Intent
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.graph.isStaleReport
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class ProtocolHardeningTest {

    private val endpoint = MeshEndpoint("192.168.49.1", 45454)
    private var now = 10_000L

    @Before
    fun setUp() {}

    @After
    fun tearDown() {}

    /**
     * WP-40.1 (A17): MeshGraphRepository report staleness pure function tests (CODE-15).
     */
    @Test
    fun restartedPeer_epochChange_acceptsNewReport() {
        assertFalse(isStaleReport(0xAAAA, 900, 0xBBBB, 1))
    }

    @Test
    fun sameEpochOlderSeq_isStale() {
        assertTrue(isStaleReport(7, 900, 7, 899))
    }

    @Test
    fun sequenceWraparound_sameEpoch() {
        assertFalse(isStaleReport(7, Int.MAX_VALUE, 7, Int.MIN_VALUE))
    }

    /**
     * WP-40.1: MeshGraphRepository epoch-aware integration test.
     */
    @Test
    fun epochAwareReporting_differentEpochReplaces_sameEpochSequenceFilters() {
        val selfId = UUID.randomUUID().toString()
        val peerA = UUID.randomUUID().toString()
        val peerB = UUID.randomUUID().toString()
        val peerC = UUID.randomUUID().toString()
        val peerD = UUID.randomUUID().toString()

        val repo = MeshGraphRepository(selfId, "SelfNode", clock = { now })

        // Initial report with epoch = 2, sequence = 5
        val acceptedInitial = repo.onHello(
            sourceId = peerA,
            name = "Peer A",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = listOf(peerB),
            sequence = 5,
            epoch = 2
        )
        assertTrue("Initial report should be accepted", acceptedInitial)
        assertEquals(listOf(peerB, selfId).sorted(), repo.currentSnapshot().neighborsOf(peerA))

        // Same epoch = 2, lower sequence = 4: rejected as stale
        val rejectedLowerSeq = repo.onHello(
            sourceId = peerA,
            name = "Peer A",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = listOf(peerC),
            sequence = 4,
            epoch = 2
        )
        assertFalse("Report with equal epoch but lower sequence must be rejected", rejectedLowerSeq)

        // Same epoch = 2, higher sequence = 6: accepted
        val acceptedHigherSeq = repo.onHello(
            sourceId = peerA,
            name = "Peer A",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = listOf(peerC),
            sequence = 6,
            epoch = 2
        )
        assertTrue("Report with equal epoch and higher sequence must be accepted", acceptedHigherSeq)
        assertEquals(listOf(peerC, selfId).sorted(), repo.currentSnapshot().neighborsOf(peerA))

        // Different epoch = 3, reset sequence = 1: accepted because different epoch always replaces
        val acceptedNewEpoch = repo.onHello(
            sourceId = peerA,
            name = "Peer A",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = listOf(peerD),
            sequence = 1,
            epoch = 3
        )
        assertTrue("Report with different epoch must be accepted regardless of sequence", acceptedNewEpoch)
        assertEquals(listOf(peerD, selfId).sorted(), repo.currentSnapshot().neighborsOf(peerA))
    }

    /**
     * WP-40.2: Set ACCEPT_LEGACY_NONCE = false (A18).
     * Legacy nonces lacking sourceId prefix are rejected by default.
     */
    @Test
    fun legacyNonce_rejectedByDefault() {
        val selfId = UUID.randomUUID().toString()
        val peerA = UUID.randomUUID().toString()

        val session = OfflineSession.create(now)
        val sManager = SessionManager(session, selfId, clock = { now })
        val repo = MeshGraphRepository(selfId, "SelfNode", clock = { now })
        val table = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(clock = { now })
        val mockLink = mockk<MeshLink>(relaxed = true)

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "SelfNode",
            sessionManager = sManager,
            graphRepository = repo,
            routingTable = table,
            duplicateCache = dupCache,
            ttlPolicy = ttl,
            link = mockLink,
            clock = { now },
            acceptLegacyNonce = false
        )

        // Encode a valid HELLO payload
        val helloBytes = HelloCodec.encode(HelloPayload(name = "peerA", graphVersion = 1, neighborIds = listOf(selfId)))
        val epoch = 1
        val sequence = 10
        val typeCode = MeshPacketType.HELLO.code
        val audioFormat = AudioFormatId.PCM16_16K_MONO

        val aad = PayloadCrypto.aad(
            sessionId = session.sessionId,
            sourceId = peerA,
            typeCode = typeCode,
            epoch = epoch,
            sequence = sequence,
            captureTimestamp = now,
            audioFormat = audioFormat
        )

        // Encrypt with LEGACY nonce (without sourceId prefix)
        val legacyNonce = PayloadCrypto.nonce(epoch = epoch, sequence = sequence, typeCode = typeCode)
        val encryptedLegacyPayload = PayloadCrypto.seal(session.payloadKey(), legacyNonce, aad, helloBytes)

        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = peerA,
            sequence = sequence,
            captureTimestamp = now,
            ttl = 5,
            hopCount = 0,
            previousHopId = peerA,
            audioFormat = audioFormat,
            epoch = epoch,
            payload = encryptedLegacyPayload
        )

        val frame = MeshFrame(endpoint, MeshPacketCodec.encode(packet, session.authKey()))

        // 1. By default: legacy nonce is rejected
        val outcomeDefault = engine.onIncomingFrame(frame)
        assertTrue(
            "Legacy nonce packet must be dropped when ACCEPT_LEGACY_NONCE is false",
            outcomeDefault is ReceiveOutcome.Dropped && outcomeDefault.reason == ReceiveOutcome.DropReason.BAD_PAYLOAD
        )
    }

    @Test
    fun legacyNonce_acceptedWhenExplicitlyEnabled() {
        val selfId = UUID.randomUUID().toString()
        val peerA = UUID.randomUUID().toString()

        val session = OfflineSession.create(now)
        val sManager = SessionManager(session, selfId, clock = { now })
        val repo = MeshGraphRepository(selfId, "SelfNode", clock = { now })
        val table = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(clock = { now })
        val mockLink = mockk<MeshLink>(relaxed = true)

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "SelfNode",
            sessionManager = sManager,
            graphRepository = repo,
            routingTable = table,
            duplicateCache = dupCache,
            ttlPolicy = ttl,
            link = mockLink,
            clock = { now },
            acceptLegacyNonce = true
        )

        val helloBytes = HelloCodec.encode(HelloPayload(name = "peerA", graphVersion = 1, neighborIds = listOf(selfId)))
        val epoch = 1
        val sequence = 10
        val typeCode = MeshPacketType.HELLO.code
        val audioFormat = AudioFormatId.PCM16_16K_MONO

        val aad = PayloadCrypto.aad(
            sessionId = session.sessionId,
            sourceId = peerA,
            typeCode = typeCode,
            epoch = epoch,
            sequence = sequence,
            captureTimestamp = now,
            audioFormat = audioFormat
        )

        val legacyNonce = PayloadCrypto.nonce(epoch = epoch, sequence = sequence, typeCode = typeCode)
        val encryptedLegacyPayload = PayloadCrypto.seal(session.payloadKey(), legacyNonce, aad, helloBytes)

        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = peerA,
            sequence = sequence,
            captureTimestamp = now,
            ttl = 5,
            hopCount = 0,
            previousHopId = peerA,
            audioFormat = audioFormat,
            epoch = epoch,
            payload = encryptedLegacyPayload
        )

        val frame = MeshFrame(endpoint, MeshPacketCodec.encode(packet, session.authKey()))

        val outcome = engine.onIncomingFrame(frame)
        assertTrue(
            "Legacy nonce packet must be accepted when acceptLegacyNonce is true",
            outcome is ReceiveOutcome.Accepted
        )
    }

    /**
     * WP-40.3: Manifest Wi-Fi permissions hardened (A19).
     */
    @Test
    fun manifest_wifiPermissionsHardened() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("Manifest must document Wi-Fi permissions rationale", manifest.contains("Wi-Fi and Peer Discovery Permissions Rationale (A19)"))
        assertFalse("CHANGE_WIFI_STATE must be removed from manifest", manifest.contains("android.permission.CHANGE_WIFI_STATE"))
        assertFalse("NEARBY_WIFI_DEVICES must be removed from manifest", manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue("ACCESS_WIFI_STATE must be retained", manifest.contains("android.permission.ACCESS_WIFI_STATE"))
        assertTrue("CHANGE_WIFI_MULTICAST_STATE must be retained", manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
    }

    /**
     * WP-40.4: MeshDebugReceiver permission & security hardening (A20, A38).
     */
    @Test
    fun debugManifest_declaresDumpPermission() {
        val debugManifest = File("src/debug/AndroidManifest.xml").readText()
        assertTrue(
            "Debug receiver must be guarded with android:permission=\"android.permission.DUMP\"",
            debugManifest.contains("android:permission=\"android.permission.DUMP\"")
        )
    }

    /**
     * WP-40.4: parsePeerFilter pure function tests.
     */
    @Test
    fun receiverActionParsing() {
        // Multiple comma-separated prefixes
        val filter = parsePeerFilter("node-1, node-2 , node-3")
        assertNotNull(filter)
        assertTrue(filter?.invoke("node-1-abc") == true)
        assertTrue(filter?.invoke("node-2-xyz") == true)
        assertTrue(filter?.invoke("node-3") == true)
        assertFalse(filter?.invoke("node-4") == true)

        // Null or empty clears filter
        assertNull(parsePeerFilter(null))
        assertNull(parsePeerFilter(""))
        assertNull(parsePeerFilter("  ,   "))
    }

    /**
     * WP-40.4: debugFilter_noopWhenNotDebug
     */
    @Test
    fun debugFilter_noopWhenNotDebug() {
        val receiver = MeshDebugReceiver(isDebug = false)
        MeshDebugReceiver.debugPeerFilter = null
        val mockContext = mockk<Context>(relaxed = true)

        val intent = mockk<Intent>()
        every { intent.action } returns MeshDebugReceiver.ACTION_SET_PEER_FILTER
        every { intent.getStringExtra(MeshDebugReceiver.EXTRA_ALLOW) } returns "node-1,node-2"
        every { intent.getStringExtra(MeshDebugReceiver.EXTRA_ALLOWED_PEERS) } returns null

        receiver.onReceive(mockContext, intent)
        assertNull("debugPeerFilter must not be modified when isDebug is false", MeshDebugReceiver.debugPeerFilter)
    }
}

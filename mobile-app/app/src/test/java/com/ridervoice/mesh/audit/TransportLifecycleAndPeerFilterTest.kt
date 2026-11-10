package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.PeerFilterHolder
import com.ridervoice.mesh.forwarding.PeerFilterPort
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TransportLifecycleAndPeerFilterTest {

    private class FakeMeshLink : MeshLink {
        override val linkId = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: kotlinx.coroutines.flow.SharedFlow<MeshFrame> = kotlinx.coroutines.flow.MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    @Before
    fun setUp() {
        PeerFilterHolder.reset()
    }

    @After
    fun tearDown() {
        PeerFilterHolder.reset()
    }

    @Test
    fun sessionExpiry_cleansUpTransportCompletely_withVirtualTime() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var virtualTime = 10_000L
        val session = OfflineSession.fromCode("1234567890ABCDEF", now = virtualTime, expiresAtOverride = virtualTime + 5_000L)!!
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "RiderA")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            clock = { virtualTime },
            dispatcher = testDispatcher,
            gateAllowed = { true },
            linkFactory = { FakeMeshLink() },
            broadcastProvider = { emptyList() }
        )

        transport.start(VoiceSession.Offline(session))
        testScheduler.runCurrent()

        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)

        // Advance time past expiry (remaining = 5_000ms)
        virtualTime = 16_000L
        testScheduler.advanceTimeBy(5_001L)
        testScheduler.runCurrent()

        assertEquals("State must be STOPPED on expiry", VoiceTransportState.STOPPED, transport.state.value)
        assertEquals("Arbiter owner must be NONE", AudioOwner.NONE, arbiter.owner.value)
        assertFalse("Mic toggle must be reset", transport.micController.micToggleOn.value)
        transport.stop()
    }

    @Test
    fun startSession_whenEnableAudioFails_setsStateFailed() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val now = 10_000L
        val session = OfflineSession.create(now = now)
        val arbiter = AudioOwnershipArbiter()
        // Claim arbiter with LIVEKIT so enableAudio cannot claim offline ownership
        arbiter.claimLiveKit()

        val identity = MeshIdentity(UUID.randomUUID().toString(), "RiderB")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            clock = { now },
            dispatcher = testDispatcher,
            gateAllowed = { true },
            linkFactory = { FakeMeshLink() },
            broadcastProvider = { emptyList() }
        )

        transport.start(VoiceSession.Offline(session))
        testScheduler.runCurrent()

        assertEquals("State must transition to FAILED on audio init failure", VoiceTransportState.FAILED, transport.state.value)
        transport.stop()
    }

    @Test
    fun peerFilterHolder_relaysMultiHop_andRestoresFullMesh() {
        val now = 10_000L
        val session = OfflineSession.create(now)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val cId = UUID.randomUUID().toString()

        val mockLink = mockk<MeshLink>(relaxed = true)
        val sessionMgr = SessionManager(session, cId, { now })
        sessionMgr.admit(aId)
        sessionMgr.admit(bId)

        val shortIdDir = ShortIdDirectory()
        shortIdDir.register(aId)
        shortIdDir.register(bId)

        val engineC = ForwardingEngine(
            selfId = cId,
            selfName = "C",
            sessionManager = sessionMgr,
            graphRepository = MeshGraphRepository(cId, "C", { now }),
            routingTable = RoutingTable(),
            replayWindow = ReplayWindow(),
            shortIdDirectory = shortIdDir,
            ttlPolicy = TtlPolicy(clock = { now }),
            link = mockLink,
            clock = { now },
            peerFilterPort = { id -> PeerFilterHolder.port?.shouldAccept(id) ?: true }
        )

        val voicePayload = ByteArray(640) { 0x11 }

        // Packet originated by A, received directly from A (previousHopId = aId, hopCount = 0)
        val directPacketFromA = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = aId,
            sequence = 1,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            previousHopId = aId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            payload = voicePayload
        )
        val directFrame = MeshFrame(MeshEndpoint("10.0.0.1", MeshConfig.MESH_PORT), MeshPacketCodec.encode(directPacketFromA, session.payloadKey()))

        // Set peer filter on C to reject previousHopId == aId (forcing traffic through B)
        PeerFilterHolder.port = PeerFilterPort { id -> id != aId }

        val directOutcome = engineC.onIncomingFrame(directFrame)
        assertTrue("Direct packet from A must be rejected by peer filter", directOutcome is ReceiveOutcome.Dropped)

        // Relayed packet from B (previousHopId = bId, hopCount = 1, ttl = DEFAULT_TTL - 1)
        val relayedPacketViaB = directPacketFromA.copy(
            sequence = 2,
            ttl = MeshConfig.DEFAULT_TTL - 1,
            hopCount = 1,
            previousHopId = bId
        )
        val relayedWire = MeshPacketCodec.encode(relayedPacketViaB, session.payloadKey())
        val relayedFrame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.MESH_PORT), relayedWire)

        val relayedOutcome = engineC.onIncomingFrame(relayedFrame)
        assertTrue("Relayed packet via B must be accepted: $relayedOutcome", relayedOutcome is ReceiveOutcome.Accepted)

        // Clear peer filter -> direct packet from A is now accepted
        PeerFilterHolder.reset()
        val directPacket2 = directPacketFromA.copy(sequence = 3)
        val directFrame2 = MeshFrame(MeshEndpoint("10.0.0.1", MeshConfig.MESH_PORT), MeshPacketCodec.encode(directPacket2, session.payloadKey()))

        val directOutcome2 = engineC.onIncomingFrame(directFrame2)
        assertTrue("Direct packet from A must be accepted after clearing filter: $directOutcome2", directOutcome2 is ReceiveOutcome.Accepted)
    }

    @Test
    fun releaseBuildInvariant_peerFilterHolderNull() {
        // By default, PeerFilterHolder.port is null
        assertNull(PeerFilterHolder.port)
    }

    @Test
    fun droppedBadVersion_incrementsCounterOnBadVersion() {
        val now = 10_000L
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val mockLink = mockk<MeshLink>(relaxed = true)

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = SessionManager(session, selfId, { now }),
            graphRepository = MeshGraphRepository(selfId, "Self", { now }),
            routingTable = RoutingTable(),
            replayWindow = ReplayWindow(),
            ttlPolicy = TtlPolicy(clock = { now }),
            link = mockLink,
            clock = { now }
        )

        assertEquals(0L, engine.counters().droppedBadVersion)

        // Craft a frame with wire version 2 instead of 4
        val validPkt = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = UUID.randomUUID().toString(),
            sequence = 1,
            ttl = 5,
            hopCount = 0,
            previousHopId = UUID.randomUUID().toString(),
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            payload = ByteArray(640)
        )
        val encoded = MeshPacketCodec.encode(validPkt, session.payloadKey())
        // Offset 0 high 3 bits is version in MeshPacketV4
        encoded[0] = ((2 shl 5) or (encoded[0].toInt() and 0x1F)).toByte()

        val outcome = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), encoded))
        assertTrue("Bad version frame must be dropped", outcome is ReceiveOutcome.Dropped)
        assertEquals("droppedBadVersion counter must increment", 1L, engine.counters().droppedBadVersion)
    }
}

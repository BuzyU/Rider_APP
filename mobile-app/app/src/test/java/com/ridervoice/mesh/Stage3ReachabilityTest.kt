package com.ridervoice.mesh

import android.content.Context
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.InMemoryMeshKeyValueStore
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NoNetworkLocks
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.Clock
import com.ridervoice.mesh.switching.GroupConnectivityStatus
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.switching.computeGroupConnectivity
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkObserver
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class Stage3ReachabilityTest {

    private class DynamicTestSettings(
        initMesh: Boolean = true,
        initFallback: Boolean = true,
        initStandby: Boolean = true
    ) : MeshRuntimeSettings {
        val flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = initMesh,
                automaticFallbackEnabled = initFallback,
                warmStandbyEnabled = initStandby
            )
        )
        override val offlineMeshEnabled: Boolean get() = flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled: Boolean get() = flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled: Boolean get() = flow.value.warmStandbyEnabled
        override val settingsFlow: StateFlow<MeshSettingsSnapshot> = flow.asStateFlow()

        fun setEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(
                offlineMeshEnabled = enabled,
                automaticFallbackEnabled = enabled,
                warmStandbyEnabled = enabled
            )
        }
    }

    private class FakeLink(val linkEndpoints: MutableSet<MeshEndpoint> = mutableSetOf()) : MeshLink {
        override val linkId: String = "fake"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        override fun endpoints(): Set<MeshEndpoint> = linkEndpoints.toSet()
        override fun noteAuthenticatedEndpoint(endpoint: MeshEndpoint) {
            linkEndpoints.add(endpoint)
        }
    }

    @Before
    fun setUp() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun nameChange_beforeSessionStart_isUsed() = runTest {
        val store = InMemoryMeshKeyValueStore()
        var currentName = "InitialRider"
        val identityRepo = MeshIdentityRepository(store, { currentName }, { "test-device-id" })

        val arbiter = AudioOwnershipArbiter()
        val transport = OfflineMeshVoiceTransport(
            identity = identityRepo.get(),
            identityProvider = { identityRepo.get() },
            audioOwnershipArbiter = arbiter,
            locks = NoNetworkLocks,
            gateAllowed = { true }
        )

        // Change rider name in repository before starting session
        currentName = "UpdatedRider"
        identityRepo.setDisplayName("UpdatedRider")

        val session = OfflineSession.create(1000L)
        transport.startDiscoveryOnly(session)

        assertEquals("startDiscoveryOnly must pick up the name from identityProvider", "UpdatedRider", transport.currentIdentity.displayName)
        transport.stop()
    }

    @Test
    fun authenticatedDirect_registers() {
        val selfId = UUID.randomUUID().toString()
        val peer1Id = UUID.randomUUID().toString()
        val link = FakeLink()
        val session = OfflineSession.create(1000L)
        val sManager = SessionManager(session, selfId, { 1000L })
        val gRepo = MeshGraphRepository(selfId, "Me", { 1000L })
        val rTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { 1000L })
        val ttlPolicy = TtlPolicy(clock = { 1000L })

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Me",
            sessionManager = sManager,
            graphRepository = gRepo,
            routingTable = rTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { 1000L }
        )

        val senderEndpoint = MeshEndpoint("192.168.1.50", 45454)
        val helloPayload = HelloCodec.encode(HelloPayload(name = "Peer1", graphVersion = 1, neighborIds = emptyList()))
        val aad = PayloadCrypto.aad(session.sessionId, peer1Id, MeshPacketType.HELLO.code, 1, 1, 1000L, AudioFormatId.PCM16_16K_MONO)
        val nonce = PayloadCrypto.nonce(1, 1, MeshPacketType.HELLO.code, peer1Id)
        val sealed = PayloadCrypto.seal(session.payloadKey(), nonce, aad, helloPayload)

        val directPacket = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = peer1Id,
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 3,
            hopCount = 0, // Direct!
            previousHopId = peer1Id,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = sealed
        )
        val wire = MeshPacketCodec.encode(directPacket, session.authKey())

        val outcome = engine.onIncomingFrame(MeshFrame(senderEndpoint, wire))
        assertTrue("Direct authenticated packet must be accepted: $outcome", outcome is ReceiveOutcome.Accepted)
        assertTrue("Direct authenticated endpoint must be registered", link.endpoints().contains(senderEndpoint))
    }

    @Test
    fun relayed_doesNotRegister() {
        val selfId = UUID.randomUUID().toString()
        val peer1Id = UUID.randomUUID().toString()
        val relay1Id = UUID.randomUUID().toString()
        val link = FakeLink()
        val session = OfflineSession.create(1000L)
        val sManager = SessionManager(session, selfId, { 1000L })
        val gRepo = MeshGraphRepository(selfId, "Me", { 1000L })
        val rTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { 1000L })
        val ttlPolicy = TtlPolicy(clock = { 1000L })

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Me",
            sessionManager = sManager,
            graphRepository = gRepo,
            routingTable = rTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { 1000L }
        )

        val relayEndpoint = MeshEndpoint("192.168.1.99", 45454)
        val helloPayload = HelloCodec.encode(HelloPayload(name = "Peer1", graphVersion = 1, neighborIds = emptyList()))
        val aad = PayloadCrypto.aad(session.sessionId, peer1Id, MeshPacketType.HELLO.code, 1, 1, 1000L, AudioFormatId.PCM16_16K_MONO)
        val nonce = PayloadCrypto.nonce(1, 1, MeshPacketType.HELLO.code, peer1Id)
        val sealed = PayloadCrypto.seal(session.payloadKey(), nonce, aad, helloPayload)

        val relayedPacket = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = peer1Id,
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 2,
            hopCount = 1, // Relayed!
            previousHopId = relay1Id,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = sealed
        )
        val wire = MeshPacketCodec.encode(relayedPacket, session.authKey())

        val outcome = engine.onIncomingFrame(MeshFrame(relayEndpoint, wire))
        assertTrue("Relayed authenticated packet is accepted for routing", outcome is ReceiveOutcome.Accepted)
        assertFalse("Relayed packet must NEVER register endpoint", link.endpoints().contains(relayEndpoint))
    }

    @Test
    fun forged_doesNotRegister() {
        val selfId = UUID.randomUUID().toString()
        val attackerId = UUID.randomUUID().toString()
        val link = FakeLink()
        val session = OfflineSession.create(1000L)
        val sManager = SessionManager(session, selfId, { 1000L })
        val gRepo = MeshGraphRepository(selfId, "Me", { 1000L })
        val rTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { 1000L })
        val ttlPolicy = TtlPolicy(clock = { 1000L })

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Me",
            sessionManager = sManager,
            graphRepository = gRepo,
            routingTable = rTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = { 1000L }
        )

        val attackerEndpoint = MeshEndpoint("192.168.1.66", 45454)
        val badPacket = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = session.sessionId,
            sourceId = attackerId,
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 3,
            hopCount = 0,
            previousHopId = attackerId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = ByteArray(32) { 0x55.toByte() } // Corrupted payload
        )
        val wire = MeshPacketCodec.encode(badPacket, session.authKey())

        val outcome = engine.onIncomingFrame(MeshFrame(attackerEndpoint, wire))
        assertTrue("Corrupted payload must be dropped", outcome is ReceiveOutcome.Dropped)
        assertFalse("Forged packet must not register endpoint", link.endpoints().contains(attackerEndpoint))
    }

    @Test
    fun endpointExpires() {
        var virtualTime = 10_000L
        val link = UdpMeshLink(
            bindPort = 45454,
            gateAllowed = { true },
            expiryWindowMs = 15_000L,
            clock = { virtualTime }
        )

        val ep = MeshEndpoint("192.168.1.20", 45454)
        link.noteAuthenticatedEndpoint(ep)

        assertTrue("Endpoint registered at 10s is present", link.endpoints().contains(ep))

        // Advance clock by 10s (now 20s, within 15s window)
        virtualTime = 20_000L
        assertTrue("Endpoint at 20s is still within window", link.endpoints().contains(ep))

        // Advance clock to 26s (>15s elapsed)
        virtualTime = 26_000L
        assertFalse("Endpoint must expire after 15s without refresh", link.endpoints().contains(ep))
    }

    @Test
    fun backupSessionReactiveAvailabilityAndExpiryTimer() = runTest {
        var virtualClock = 10_000L
        val holder = OfflineSessionHolder()
        val secretProvider = HolderOfflineSecretProvider(holder) { virtualClock }

        val settings = DynamicTestSettings(initMesh = true, initFallback = true, initStandby = true)
        OfflineMeshGate.install(settings)

        val lkTransport = mockk<VoiceTransport>(relaxed = true)
        val meshTransport = mockk<VoiceTransport>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = lkTransport,
            meshTransport = meshTransport,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { virtualClock },
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val online = VoiceSession.Online("wss://livekit", "room-123", { "tok" }, "room-123")
        coordinator.setOnlineSession(online)

        // Initial state: no backup session -> NO_SHARED_SECRET
        testScheduler.runCurrent()
        assertEquals(OfflineAvailability.NO_SHARED_SECRET, coordinator.offlineAvailability.value)

        // Set backup session in holder (valid for 8 hours = 28_800_000 ms)
        val backupSession = OfflineSession.create(virtualClock)
        holder.setBackup(backupSession)

        testScheduler.runCurrent()
        assertEquals("Setting backup session flips availability to AVAILABLE", OfflineAvailability.AVAILABLE, coordinator.offlineAvailability.value)

        // Advance time right before expiry
        val remainingMs = backupSession.expiresAt - virtualClock
        virtualClock += (remainingMs - 1_000L)
        testScheduler.advanceTimeBy(remainingMs - 1_000L)
        assertEquals("Right before expiry it is still AVAILABLE", OfflineAvailability.AVAILABLE, coordinator.offlineAvailability.value)

        // Advance time past expiry
        virtualClock += 2_000L
        testScheduler.advanceTimeBy(2_000L)
        assertEquals("After expiry it flips to NO_SHARED_SECRET", OfflineAvailability.NO_SHARED_SECRET, coordinator.offlineAvailability.value)
    }

    @Test
    fun holderOfflineSecretProvider_returnsSessionOnlyIfNotExpired() {
        var virtualTime = 1_000L
        val holder = OfflineSessionHolder()
        val provider = HolderOfflineSecretProvider(holder) { virtualTime }

        assertNull(provider.getSessionForOnlineRide("any-room"))

        val session = OfflineSession.create(virtualTime)
        holder.setBackup(session)

        val retrieved = provider.getSessionForOnlineRide("any-room")
        assertNotNull("Non-expired backup session is returned", retrieved)
        assertEquals(session.sessionId, retrieved!!.sessionId)

        // Fast-forward past expiry
        virtualTime = session.expiresAt + 1L
        assertNull("Expired backup session returns null", provider.getSessionForOnlineRide("any-room"))
    }

    @Test
    fun ghostLiveKit_treatedAsLeft_notMixed() {
        val peers = listOf(
            UnifiedVoicePeer(
                id = "p1",
                name = "Active Rider",
                isOnline = true,
                transport = PeerTransportKind.ONLINE_LIVEKIT,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            ),
            UnifiedVoicePeer(
                id = "p2_ghost",
                name = "Ghost Rider",
                isOnline = false, // Ghost / disconnected
                transport = PeerTransportKind.ONLINE_LIVEKIT,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        )

        val status = computeGroupConnectivity(isLocalOffline = false, peers = peers)
        assertEquals("Ghost LiveKit participants must be treated as left, resulting in ONLINE_ALL", GroupConnectivityStatus.ONLINE_ALL, status)
    }

    @Test
    fun startAfterInstall_usesInstalledSettings() = runTest {
        val settings = DynamicTestSettings(initMesh = true, initFallback = true, initStandby = true)
        OfflineMeshGate.install(settings)
        assertTrue("Installed settings must report isOfflineAllowed == true", OfflineMeshGate.isOfflineAllowed())

        settings.setEnabled(false)
        assertFalse("Disabled settings must report isOfflineAllowed == false", OfflineMeshGate.isOfflineAllowed())
    }

    @Test
    fun noSocketsWhileGateClosed() = runTest {
        val settings = DynamicTestSettings(initMesh = false, initFallback = false, initStandby = false)
        OfflineMeshGate.install(settings)

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val netObserver = mockk<NetworkObserver>(relaxed = true)
        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        val mockMeshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val identityRepo = mockk<MeshIdentityRepository>(relaxed = true)
        val healthSignalsProvider = mockk<LocalHealthSignalsProvider>(relaxed = true)
        val holder = OfflineSessionHolder()
        val secretProvider = HolderOfflineSecretProvider(holder)

        every { mockMeshTransport.state } returns MutableStateFlow(VoiceTransportState.STOPPED)

        val liveRide = ActiveRideRepository.ActiveRide(
            roomId = "room-1",
            displayName = "Rider",
            isHost = false,
            phase = ActiveRideRepository.Phase.LIVE
        )
        every { rideRepo.state } returns MutableStateFlow(liveRide)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            coordinator = coordinator,
            meshTransport = mockMeshTransport,
            healthSignalsProvider = healthSignalsProvider,
            secretProvider = secretProvider,
            dispatcher = testDispatcher
        )

        manager.start()
        testScheduler.advanceTimeBy(5_000L)

        // Because gate is closed (All off), standby discovery is never triggered
        io.mockk.coVerify(exactly = 0) { coordinator.ensureWarmStandbyDiscovery(any()) }
        manager.stop()
    }

    @Test
    fun managerStartsWhenSettingEnabledAtRuntime() = runTest {
        val settings = DynamicTestSettings(initMesh = false, initFallback = false, initStandby = false)
        OfflineMeshGate.install(settings)

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val netObserver = mockk<NetworkObserver>(relaxed = true)
        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        val mockMeshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val identityRepo = mockk<MeshIdentityRepository>(relaxed = true)
        val healthSignalsProvider = mockk<LocalHealthSignalsProvider>(relaxed = true)

        val holder = OfflineSessionHolder()
        val backupSession = OfflineSession.create(1000L)
        holder.setBackup(backupSession)
        val secretProvider = HolderOfflineSecretProvider(holder) { 1000L }

        every { mockMeshTransport.state } returns MutableStateFlow(VoiceTransportState.STOPPED)
        every { mockMeshTransport.participants } returns MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
        every { lkManager.participants } returns MutableStateFlow<List<Participant>>(emptyList())

        val liveRide = ActiveRideRepository.ActiveRide(
            roomId = "room-1",
            displayName = "Rider",
            isHost = false,
            phase = ActiveRideRepository.Phase.LIVE
        )
        every { rideRepo.state } returns MutableStateFlow(liveRide)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            coordinator = coordinator,
            meshTransport = mockMeshTransport,
            healthSignalsProvider = healthSignalsProvider,
            secretProvider = secretProvider,
            dispatcher = testDispatcher
        )

        manager.start()
        testScheduler.advanceTimeBy(2_000L)
        io.mockk.coVerify(exactly = 0) { coordinator.ensureWarmStandbyDiscovery(any()) }

        // Enable settings at runtime
        settings.setEnabled(true)
        testScheduler.advanceTimeBy(2_000L)

        io.mockk.coVerify(atLeast = 1) { coordinator.ensureWarmStandbyDiscovery(any()) }
        manager.stop()
    }

    @Test
    fun managerStopsWorkWhenSettingDisabled() = runTest {
        val settings = DynamicTestSettings(initMesh = true, initFallback = true, initStandby = true)
        OfflineMeshGate.install(settings)

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val netObserver = mockk<NetworkObserver>(relaxed = true)
        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        val mockMeshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val identityRepo = mockk<MeshIdentityRepository>(relaxed = true)
        val healthSignalsProvider = mockk<LocalHealthSignalsProvider>(relaxed = true)

        val holder = OfflineSessionHolder()
        val backupSession = OfflineSession.create(1000L)
        holder.setBackup(backupSession)
        val secretProvider = HolderOfflineSecretProvider(holder) { 1000L }

        val transportState = MutableStateFlow(VoiceTransportState.CONNECTED)
        every { mockMeshTransport.state } returns transportState
        every { mockMeshTransport.participants } returns MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
        every { lkManager.participants } returns MutableStateFlow<List<Participant>>(emptyList())

        val liveRide = ActiveRideRepository.ActiveRide(
            roomId = "room-1",
            displayName = "Rider",
            isHost = false,
            phase = ActiveRideRepository.Phase.LIVE
        )
        every { rideRepo.state } returns MutableStateFlow(liveRide)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            coordinator = coordinator,
            meshTransport = mockMeshTransport,
            healthSignalsProvider = healthSignalsProvider,
            secretProvider = secretProvider,
            dispatcher = testDispatcher
        )

        manager.start()
        testScheduler.advanceTimeBy(2_000L)

        // Disable settings at runtime
        settings.setEnabled(false)
        testScheduler.advanceTimeBy(2_000L)

        io.mockk.coVerify(atLeast = 1) { mockMeshTransport.stop() }
        manager.stop()
    }
}

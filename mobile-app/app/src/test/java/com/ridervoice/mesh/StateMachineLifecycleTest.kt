package com.ridervoice.mesh

import com.ridervoice.mesh.identity.InMemoryMeshKeyValueStore
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.identity.RiderNameProvider
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
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkObserver
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineLifecycleTest {

    private val healthySignals = LocalHealthSignals(
        isValidatedInternet = true,
        isLiveKitConnected = true,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 0,
        isHealthProbeSuccessful = true,
        hasValidMeshRoute = true,
        offlineAvailable = true
    )

    private val totalLossSignals = LocalHealthSignals(
        isValidatedInternet = false,
        isLiveKitConnected = false,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 3,
        isHealthProbeSuccessful = false,
        hasValidMeshRoute = true,
        offlineAvailable = true
    )

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
    }

    @Test
    fun beginSession_resetsAllTimers() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )

        // Drive into suspect
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // Begin new session
        now += 5_000L
        sm.beginSession("ride-123", now)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)

        // Evaluate healthy: must stay ONLINE
        sm.evaluate(healthySignals)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun secondRide_startsClean() = runTest {
        var now = 200_000L
        var offlineSwitches = 0
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = {
                offlineSwitches++
                true
            }
        )

        // Ride 1 falls back to mesh
        sm.beginSession("ride-1", now)
        sm.evaluate(totalLossSignals)
        now += 10_001L
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
        assertEquals(1, offlineSwitches)

        // Ride 2 starts fresh
        now += 1_000L
        sm.beginSession("ride-2", now)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)

        // Ride 2 should not be restricted by Ride 1's dwell time or flapping history
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)
        now += 10_001L
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
        assertEquals(2, offlineSwitches)
    }

    @Test
    fun lobbyAndEndingPhases_neverTransition() = runTest {
        var virtualTime = 50_000L
        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val netObserver = mockk<NetworkObserver>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()
        val mockLkTransport = mockk<VoiceTransport>(relaxed = true)
        val mockMeshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = mockLkTransport,
            meshTransport = mockMeshTransport,
            audioOwnershipArbiter = arbiter
        )
        val identityRepo = MeshIdentityRepository(InMemoryMeshKeyValueStore(), RiderNameProvider { "Rider1" })

        every { mockMeshTransport.state } returns MutableStateFlow(com.ridervoice.mesh.transport.VoiceTransportState.STOPPED)
        every { mockMeshTransport.participants } returns MutableStateFlow(emptyList())
        every { lkManager.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { lkManager.participants } returns MutableStateFlow(emptyList())
        every { netObserver.isConnected() } returns false

        val rideStateFlow = MutableStateFlow<ActiveRideRepository.ActiveRide?>(
            ActiveRideRepository.ActiveRide(
                roomId = "room-lobby",
                displayName = "Lobby Ride",
                isHost = true,
                phase = ActiveRideRepository.Phase.LOBBY
            )
        )
        every { rideRepo.state } returns rideStateFlow

        val healthSignalsProvider = LocalHealthSignalsProvider(
            networkObserver = netObserver,
            liveKitConnectionState = lkManager.connectionState,
            healthProbe = { false },
            clock = { virtualTime }
        )

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            networkObserver = netObserver,
            coordinator = coordinator,
            meshTransport = mockMeshTransport,
            identityRepo = identityRepo,
            healthSignalsProvider = healthSignalsProvider,
            clock = Clock { virtualTime },
            dispatcher = testDispatcher
        )

        manager.start()
        testScheduler.advanceTimeBy(10_000L)

        // During LOBBY phase, state machine must never evaluate transitions
        assertEquals(AutoSwitchState.ONLINE, manager.stateMachine.state.value)

        // Switch to ENDING phase
        rideStateFlow.value = ActiveRideRepository.ActiveRide(
            roomId = "room-lobby",
            displayName = "Lobby Ride",
            isHost = true,
            phase = ActiveRideRepository.Phase.ENDING
        )
        testScheduler.advanceTimeBy(10_000L)

        // During ENDING phase, state machine ends session / remains idle
        assertTrue(
            manager.stateMachine.state.value == AutoSwitchState.ONLINE ||
                manager.stateMachine.state.value == AutoSwitchState.SESSION_ENDED
        )

        manager.stop()
    }

    @Test
    fun noSecret_neverLeavesOnline() = runTest {
        var now = 300_000L
        var diagnosticsEmitted = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onEmitTransitionDiagnostic = { _, _, reason ->
                if (reason == "NO_SHARED_SECRET") diagnosticsEmitted = true
            }
        )

        // Signals show total loss, but offline is NOT available
        val signalsNoSecret = totalLossSignals.copy(offlineAvailable = false)

        sm.evaluate(signalsNoSecret, offlineAvailable = false)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
        assertTrue("NO_SHARED_SECRET diagnostic emitted", diagnosticsEmitted)

        now += 15_000L
        sm.evaluate(signalsNoSecret, offlineAvailable = false)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun degradedOnly_neverFallsBack() = runTest {
        var now = 400_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )

        // Degraded: internet is validated, but LiveKit is reconnecting and packet loss is high
        val degradedSignals = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 5,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            packetLossPercent = 65f,
            rttMs = 1200L,
            offlineAvailable = true
        )

        assertFalse("Degraded only is NOT total loss", degradedSignals.isTotalLoss())
        assertFalse("Degraded only is NOT online failing", degradedSignals.isOnlineFailing())

        sm.evaluate(degradedSignals)
        assertEquals("Must remain ONLINE on degraded connection", AutoSwitchState.ONLINE, sm.state.value)

        now += 15_000L
        sm.evaluate(degradedSignals)
        assertEquals("Must remain ONLINE even after timeout", AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun totalLoss_fallsBackAfterHysteresis() = runTest {
        var now = 500_000L
        var offlineHandoffDone = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = {
                offlineHandoffDone = true
                true
            }
        )

        // t=0: Total loss detected
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // t=5s: Before 10s confirmation window
        now += 5_000L
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)
        assertFalse(offlineHandoffDone)

        // t=10.001s: Hysteresis window satisfied
        now += 5_001L
        sm.evaluate(totalLossSignals)
        assertTrue(offlineHandoffDone)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun probeThrottle_5s() = runTest {
        var virtualTime = 10_000L
        var probeCount = 0

        val provider = LocalHealthSignalsProvider(
            isInternetValidated = { false },
            liveKitConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED),
            healthProbe = {
                probeCount++
                false
            },
            clock = { virtualTime }
        )

        // Call 1 at t=10_000: executes probe
        provider.sampleSignals()
        assertEquals(1, probeCount)

        // Calls inside 5000ms window: throttled, probeCount unchanged
        virtualTime += 1_000L
        provider.sampleSignals()
        assertEquals(1, probeCount)

        virtualTime += 2_000L
        provider.sampleSignals()
        assertEquals(1, probeCount)

        virtualTime += 1_999L // total elapsed 4999ms
        provider.sampleSignals()
        assertEquals(1, probeCount)

        // Call at elapsed 5000ms: probe allowed
        virtualTime += 1L // total elapsed 5000ms
        provider.sampleSignals()
        assertEquals(2, probeCount)
    }

    @Test
    fun noProbeWhenHealthy() = runTest {
        var probeCount = 0

        val provider = LocalHealthSignalsProvider(
            isInternetValidated = { true },
            liveKitConnectionState = MutableStateFlow(ConnectionState.CONNECTED),
            healthProbe = {
                probeCount++
                true
            }
        )

        // Healthy connection: must NEVER execute probe
        provider.sampleSignals()
        provider.sampleSignals()
        provider.sampleSignals()

        assertEquals(0, probeCount)
    }

    @Test
    fun gateClosed_noBackgroundWork() = runTest {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val netObserver = mockk<NetworkObserver>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()
        val mockLkTransport = mockk<VoiceTransport>(relaxed = true)
        val mockMeshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = mockLkTransport,
            meshTransport = mockMeshTransport,
            audioOwnershipArbiter = arbiter
        )
        val identityRepo = MeshIdentityRepository(InMemoryMeshKeyValueStore(), RiderNameProvider { "Rider1" })

        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = "room-live",
                displayName = "Live Ride",
                isHost = true,
                phase = ActiveRideRepository.Phase.LIVE
            )
        )

        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            networkObserver = netObserver,
            coordinator = coordinator,
            meshTransport = mockMeshTransport,
            identityRepo = identityRepo,
            healthSignalsProvider = LocalHealthSignalsProvider()
        )

        try {
            manager.start()
            assertEquals("Gate closed: state machine remains ONLINE without activity", AutoSwitchState.ONLINE, manager.stateMachine.state.value)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun recovery_requiresStableWindow() = runTest {
        var now = 600_000L
        var handbackCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                handbackCalled = true
                true
            }
        )

        // Fall back to offline
        sm.evaluate(totalLossSignals)
        now += 10_001L
        sm.evaluate(totalLossSignals)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Wait past 20s minimum dwell
        now += 20_001L
        sm.evaluate(healthySignals)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // Inside 8s recovery window (e.g. 4s): must remain ONLINE_RECOVERING
        now += 4_000L
        sm.evaluate(healthySignals)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)
        assertFalse(handbackCalled)

        // Satisfy 8s window
        now += 4_001L
        sm.evaluate(healthySignals)
        assertTrue(handbackCalled)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun groupStatus_onlineAll() {
        val peers = listOf(
            UnifiedVoicePeer(
                id = "peer1",
                name = "Rider 1",
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
                id = "peer2",
                name = "Rider 2",
                isOnline = true,
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
        assertEquals(GroupConnectivityStatus.ONLINE_ALL, status)
    }

    @Test
    fun groupStatus_mixed() {
        // Local online, but one peer ghost/offline
        val mixedPeers1 = listOf(
            UnifiedVoicePeer(
                id = "peer1",
                name = "Rider 1",
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
                id = "peer2",
                name = "Rider 2 (Ghost)",
                isOnline = false,
                transport = PeerTransportKind.ONLINE_LIVEKIT,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        )
        val status1 = computeGroupConnectivity(isLocalOffline = false, peers = mixedPeers1)
        assertEquals(GroupConnectivityStatus.ONLINE_ALL, status1)

        // Local online, one peer on offline mesh
        val mixedPeers2 = listOf(
            UnifiedVoicePeer(
                id = "peer1",
                name = "Rider 1",
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
                id = "peer3",
                name = "Rider 3 (Mesh)",
                isOnline = true,
                transport = PeerTransportKind.OFFLINE_MESH,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        )
        val status2 = computeGroupConnectivity(isLocalOffline = false, peers = mixedPeers2)
        assertEquals(GroupConnectivityStatus.MIXED, status2)

        // Local offline, but remote peer is online
        val status3 = computeGroupConnectivity(isLocalOffline = true, peers = mixedPeers2)
        assertEquals(GroupConnectivityStatus.MIXED, status3)
    }

    @Test
    fun groupStatus_offlineMeshOnly() {
        val meshPeers = listOf(
            UnifiedVoicePeer(
                id = "peer1",
                name = "Rider 1",
                isOnline = true,
                transport = PeerTransportKind.OFFLINE_MESH,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            ),
            UnifiedVoicePeer(
                id = "peer2",
                name = "Rider 2",
                isOnline = true,
                transport = PeerTransportKind.OFFLINE_MESH,
                hopCount = 2,
                nextHopId = "peer1",
                isDirect = false,
                isRelayed = true,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        )
        val status = computeGroupConnectivity(isLocalOffline = true, peers = meshPeers)
        assertEquals(GroupConnectivityStatus.OFFLINE_MESH_ONLY, status)
    }

    @Test
    fun groupStatus_alone() {
        // No peers
        val status1 = computeGroupConnectivity(isLocalOffline = false, peers = emptyList())
        assertEquals(GroupConnectivityStatus.ALONE, status1)

        // Local offline, 0 peers
        val status2 = computeGroupConnectivity(isLocalOffline = true, peers = emptyList())
        assertEquals(GroupConnectivityStatus.ALONE, status2)

        // Local offline, only unreachable peers
        val unreachablePeers = listOf(
            UnifiedVoicePeer(
                id = "peer1",
                name = "Rider 1",
                isOnline = false,
                transport = PeerTransportKind.OFFLINE_MESH,
                hopCount = 0,
                nextHopId = null,
                isDirect = false,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        )
        val status3 = computeGroupConnectivity(isLocalOffline = true, peers = unreachablePeers)
        assertEquals(GroupConnectivityStatus.ALONE, status3)
    }
}

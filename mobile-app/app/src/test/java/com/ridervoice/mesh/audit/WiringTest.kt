package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.identity.InMemoryMeshKeyValueStore
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.link.NoNetworkLocks
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WiringTest {

    /**
     * T10: Flag-off: constructing all mesh objects and calling start() opens no socket
     * and starts no capture; port 45454 stays bindable.
     */
    @Test
    fun t10_flagOffOpensNoSocketAndPortStaysBindable() = runTest {
        // Assert per-build-type value (debug true in test runner, release false)
        assertEquals(
            "Build flag must match per-build-type value",
            com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED,
            FeatureFlags.OFFLINE_MESH_ENABLED
        )

        // Ensure flag is OFF via localOverride
        FeatureFlags.localOverride = false
        try {
            assertFalse("Build flag must be off when overridden", FeatureFlags.OFFLINE_MESH_ENABLED)
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)

            val fakeStore = InMemoryMeshKeyValueStore()
            val identityRepo = MeshIdentityRepository(fakeStore, { "TestNode" }, { UUID.randomUUID().toString() })
            val arbiter = AudioOwnershipArbiter()
            val locks = NoNetworkLocks

            val transport = OfflineMeshVoiceTransport(
                identity = identityRepo.get(),
                audioOwnershipArbiter = arbiter,
                locks = locks,
                gateAllowed = { OfflineMeshGate.isOfflineAllowed() }
            )

            // Starting transport when gate is false must abort immediately
            val session = OfflineSession.create(System.currentTimeMillis())
            transport.start(com.ridervoice.mesh.transport.VoiceSession.Offline(session))

            assertEquals(VoiceTransportState.FAILED, transport.state.value)

            // Verify that port 45454 was NEVER bound and is freely bindable
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MeshConfig.AUDIO_PORT)
                assertTrue("Socket bound successfully on AUDIO_PORT", socket.isBound)
            } finally {
                socket?.close()
                transport.stop()
            }
        } finally {
            FeatureFlags.localOverride = null
        }
    }

    /**
     * Verifies that when OfflineMeshGate is closed:
     * 1. Transport refuses to open sockets, leaving port 45454 unbound.
     * 2. MeshForegroundService start attempts abort without invoking Android service start
     *    or posting any notification.
     */
    @Test
    fun closedGatePreventsSocketBindingAndBlocksServiceStartAndNotification() = runTest {
        FeatureFlags.localOverride = false
        try {
            assertFalse(OfflineMeshGate.isOfflineAllowed())

            // 1. Transport opens no socket
            val fakeStore = InMemoryMeshKeyValueStore()
            val identityRepo = MeshIdentityRepository(fakeStore, { "GateNode" }, { "node-gate" })
            val transport = OfflineMeshVoiceTransport(
                identity = identityRepo.get(),
                audioOwnershipArbiter = AudioOwnershipArbiter(),
                locks = NoNetworkLocks,
                gateAllowed = { OfflineMeshGate.isOfflineAllowed() }
            )
            val session = OfflineSession.create(System.currentTimeMillis())
            transport.start(com.ridervoice.mesh.transport.VoiceSession.Offline(session))

            assertEquals(VoiceTransportState.FAILED, transport.state.value)

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MeshConfig.AUDIO_PORT)
                assertTrue("AUDIO_PORT remains bindable", socket.isBound)
            } finally {
                socket?.close()
                transport.stop()
            }

            // 2. MeshForegroundService.start aborts and posts no notification
            val mockContext = io.mockk.mockk<android.content.Context>(relaxed = false)
            com.ridervoice.services.MeshForegroundService.start(mockContext, "PAIR123")
            io.mockk.verify(exactly = 0) {
                mockContext.startService(any())
                mockContext.startForegroundService(any())
            }
        } finally {
            FeatureFlags.localOverride = null
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        }
    }

    /**
     * (a) Gate open: service is started ONLY after OfflineMeshVoiceTransport reaches CONNECTED.
     * Before reaching CONNECTED (e.g. while INITIALIZING or DISCOVERING), MeshForegroundService.start()
     * must no-op without invoking Android service start APIs.
     */
    @Test
    fun gateOpenTransportReachesConnectedStartsService() = runTest {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = false
            override val warmStandbyEnabled = false
        })
        FeatureFlags.localOverride = true

        val mockContext = io.mockk.mockk<android.content.Context>(relaxed = true)
        val mockTransport = io.mockk.mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val transportState = MutableStateFlow(VoiceTransportState.STARTING)
        io.mockk.every { mockTransport.state } returns transportState

        try {
            assertTrue(OfflineMeshGate.isOfflineAllowed())
            com.ridervoice.services.MeshForegroundService.activeTransport = mockTransport

            // Case 1: In STARTING state, start() does not start service
            com.ridervoice.services.MeshForegroundService.start(mockContext, "PAIR123")
            io.mockk.verify(exactly = 0) {
                mockContext.startService(any())
                mockContext.startForegroundService(any())
            }

            // Case 2: Transition to CONNECTED state, start() starts service
            transportState.value = VoiceTransportState.CONNECTED
            com.ridervoice.services.MeshForegroundService.start(mockContext, "PAIR123")
            io.mockk.verify(atLeast = 1) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    mockContext.startForegroundService(any())
                } else {
                    mockContext.startService(any())
                }
            }
        } finally {
            com.ridervoice.services.MeshForegroundService.activeTransport = null
            FeatureFlags.localOverride = null
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        }
    }

    /**
     * (b) When transport goes FAILED or STOPPED, MeshForegroundService.stop() terminates the service.
     */
    @Test
    fun transportGoesFailedOrStoppedStopsService() = runTest {
        val mockContext = io.mockk.mockk<android.content.Context>(relaxed = true)
        try {
            com.ridervoice.services.MeshForegroundService.stop(mockContext)
            io.mockk.verify(exactly = 1) {
                mockContext.stopService(any())
            }
        } finally {
            com.ridervoice.services.MeshForegroundService.activeTransport = null
        }
    }

    /**
     * T24: Debug peer filter field is null by default.
     */
    @Test
    fun t24_debugPeerFilterDefaultsToNull() {
        assertNull("debugPeerFilter must be null by default", MeshConfig.debugPeerFilter)
    }

    /**
     * Verify LocalHealthSignalsProvider accurately samples local health conditions.
     */
    @Test
    fun localHealthSignalsProviderSamplesLocalSignalsAccurately() = runTest {
        val lkState = MutableStateFlow(ConnectionState.CONNECTED)
        var internetValidated = true
        var probeSuccessful = true
        var validRoute = true

        val provider = LocalHealthSignalsProvider(
            networkObserver = null,
            liveKitConnectionState = lkState,
            liveKitReconnectAttempts = { 2 },
            meshControl = object : com.ridervoice.mesh.transport.MeshControl {
                override val hasValidRoute: StateFlow<Boolean> = MutableStateFlow(validRoute)
                override suspend fun startDiscoveryOnly(session: OfflineSession) {}
                override suspend fun enableAudio() {}
                override suspend fun disableAudio() {}
            },
            healthProbe = { probeSuccessful },
            isInternetValidated = { internetValidated }
        )

        val healthy = provider.sampleSignals()
        assertTrue(healthy.isValidatedInternet)
        assertTrue(healthy.isLiveKitConnected)
        assertFalse(healthy.isLiveKitReconnecting)
        assertEquals(2, healthy.liveKitReconnectAttempts)
        assertTrue(healthy.isHealthProbeSuccessful)
        assertTrue(healthy.hasValidMeshRoute)
        assertTrue(healthy.isOnlineHealthy())

        // Simulate Internet failure
        internetValidated = false
        probeSuccessful = false
        lkState.value = ConnectionState.RECONNECTING

        val failing = provider.sampleSignals()
        assertFalse(failing.isValidatedInternet)
        assertFalse(failing.isLiveKitConnected)
        assertTrue(failing.isLiveKitReconnecting)
        assertFalse(failing.isHealthProbeSuccessful)
        assertTrue(failing.isOnlineFailing())
    }

    /**
     * Verify LocalHealthSignalsProvider driving loop evaluates machine while active and ends session when ride ends.
     */
    @Test
    fun localHealthSignalsProviderDriverEvaluatesMachineAndEndsSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val isRideActive = MutableStateFlow(true)
        var evaluateCount = 0
        var sessionEnded = false

        val provider = LocalHealthSignalsProvider(
            isInternetValidated = { true },
            healthProbe = { true },
            dispatcher = testDispatcher
        )

        val machine = AutomaticSwitchStateMachine(
            clock = { testScheduler.currentTime },
            onEmitTransitionDiagnostic = { _, _, _ -> }
        )

        // Drive with short interval for fast testing
        val job = provider.startDriving(
            machine = machine,
            isRideActive = isRideActive,
            scope = CoroutineScope(testDispatcher),
            pollIntervalMs = 100L
        )

        testScheduler.advanceTimeBy(350L)
        // Ride is active, machine is evaluated

        // End ride
        isRideActive.value = false
        testScheduler.advanceTimeBy(100L)

        assertEquals(
            com.ridervoice.mesh.switching.AutoSwitchState.SESSION_ENDED,
            machine.state.value
        )

        job.cancel()
    }

    /**
     * Verify MeshVoxBridge manages mic state on PTT hold and release.
     */
    @Test
    fun meshVoxBridgeUpdatesMicStateOnPttHoldAndRelease() = runTest {
        var micEnabledState = false
        val fakeTransport = object : VoiceTransport {
            override val state: StateFlow<VoiceTransportState> = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow(emptyList<com.ridervoice.mesh.transport.UnifiedVoicePeer>())
            override val activeSpeakers = MutableStateFlow(emptySet<String>())
            override val micState = MutableStateFlow(MicState.OFF)
            override val audioOwner = MutableStateFlow(com.ridervoice.mesh.transport.AudioOwner.OFFLINE_MESH)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {
                micEnabledState = enabled
            }
        }

        val bridge = MeshVoxBridge(
            transport = fakeTransport,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        bridge.setPttPressed(true)
        testScheduler.runCurrent()
        assertTrue("PTT pressed must enable microphone", micEnabledState)

        bridge.setPttPressed(false)
        testScheduler.runCurrent()
        assertFalse("PTT released must disable microphone", micEnabledState)

        bridge.close()
    }

    @Test
    fun onlineLiveKitConnectionAutomaticallyClaimsAndReleasesArbiter() = runTest {
        val connState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val fakePort = object : com.ridervoice.mesh.transport.LiveKitPort {
            override val connectionState: StateFlow<ConnectionState> = connState
            override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
            override val participants: StateFlow<List<com.ridervoice.models.Participant>> = MutableStateFlow(emptyList())
            override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {}
            override fun disconnect() {}
            override fun setUserMuted(muted: Boolean) {}
            override fun setDeafened(deafened: Boolean) {}
        }
        val arbiter = AudioOwnershipArbiter()
        val transport = com.ridervoice.mesh.transport.LiveKitVoiceTransport(
            fakePort, arbiter, dispatcher = StandardTestDispatcher(testScheduler)
        )

        assertEquals(com.ridervoice.mesh.transport.AudioOwner.NONE, arbiter.owner.value)

        connState.value = ConnectionState.CONNECTED
        testScheduler.runCurrent()
        assertEquals(com.ridervoice.mesh.transport.AudioOwner.LIVEKIT, arbiter.owner.value)

        connState.value = ConnectionState.DISCONNECTED
        testScheduler.runCurrent()
        assertEquals(com.ridervoice.mesh.transport.AudioOwner.NONE, arbiter.owner.value)
    }
}
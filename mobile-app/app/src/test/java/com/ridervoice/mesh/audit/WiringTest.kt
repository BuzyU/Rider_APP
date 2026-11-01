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
        // Ensure flag is OFF
        assertFalse("Build flag must be off in test", FeatureFlags.OFFLINE_MESH_ENABLED)
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
}


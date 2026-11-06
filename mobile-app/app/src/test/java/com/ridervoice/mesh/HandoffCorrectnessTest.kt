package com.ridervoice.mesh

import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandoffCorrectnessTest {

    private class TestLiveKitPort : LiveKitPort {
        val connectionStateFlow = MutableStateFlow(ConnectionState.CONNECTED)
        override val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()
        override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
        override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
        val isUserMutedFlow = MutableStateFlow(false)
        override val isUserMuted: StateFlow<Boolean> = isUserMutedFlow.asStateFlow()
        val deafenedFlow = MutableStateFlow(false)
        override val deafened: StateFlow<Boolean> = deafenedFlow.asStateFlow()
        var isVoxRunningState = false
        override val isVoxRunning: Boolean get() = isVoxRunningState

        var connectCount = 0
        var disconnectCount = 0

        override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {
            connectCount++
            connectionStateFlow.value = ConnectionState.CONNECTED
        }

        override fun disconnect() {
            disconnectCount++
            connectionStateFlow.value = ConnectionState.DISCONNECTED
        }

        override fun setUserMuted(muted: Boolean) {
            isUserMutedFlow.value = muted
        }

        override fun setDeafened(deafened: Boolean) {
            deafenedFlow.value = deafened
        }
    }

    private class TestMeshTransport : VoiceTransport, MeshControl {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        private val _micState = MutableStateFlow(MicState.OFF)
        override val micState: StateFlow<MicState> = _micState.asStateFlow()

        val _hasValidRoute = MutableStateFlow(true)
        override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

        var audioEnabled = false
        var failOnEnableAudio = false
        var capturePaused = false
        var disableAudioCount = 0
        var stopCount = 0

        override suspend fun startDiscoveryOnly(session: OfflineSession) {
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun enableAudio(): EnableAudioResult {
            if (failOnEnableAudio) {
                _micState.value = MicState.HARDWARE_BUSY
                return EnableAudioResult.Failure.HardwareBusy
            }
            audioEnabled = true
            capturePaused = false
            _micState.value = MicState.ON
            return EnableAudioResult.Success
        }

        override suspend fun disableAudio() {
            audioEnabled = false
            capturePaused = false
            _micState.value = MicState.OFF
            disableAudioCount++
        }

        override suspend fun pauseCaptureOnly() {
            capturePaused = true
            _micState.value = MicState.MUTED
        }

        override suspend fun resumeCaptureOnly() {
            capturePaused = false
            _micState.value = MicState.ON
        }

        override suspend fun start(session: VoiceSession) {
            audioEnabled = true
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun stop() {
            stopCount++
            disableAudio()
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            _micState.value = if (enabled) MicState.ON else MicState.OFF
        }

        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
        override suspend fun pause() { pauseCaptureOnly() }
        override suspend fun resume() { resumeCaptureOnly() }
    }

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun recovery_keepsMeshAudioUntilLiveKitConfirmed() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val lkPort = TestLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, dispatcher = testDispatcher)
        val mesh = TestMeshTransport()

        var virtualTime = 1_000L
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            clock = { virtualTime },
            dispatcher = testDispatcher
        )

        val online = VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1")
        coordinator.setOnlineSession(online)

        // Setup: Switch to OFFLINE_MESH
        coordinator.switchToTransport(PeerTransportKind.OFFLINE_MESH, VoiceSession.Offline(OfflineSession.create(1000L)))
        arbiter.tryClaimOfflineMesh()
        mesh.enableAudio()
        assertTrue("Mesh audio is enabled initially", mesh.audioEnabled)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // While LiveKit is not CONNECTED, recoverToLiveKit returns false and leaves mesh untouched
        lkPort.connectionStateFlow.value = ConnectionState.RECONNECTING
        val preResult = coordinator.recoverToLiveKit()
        assertFalse("recoverToLiveKit must return false when LiveKit is not connected", preResult)
        assertFalse("Mesh capture must NOT be paused before LiveKit is connected", mesh.capturePaused)
        assertTrue("Mesh audio playback must remain enabled (no dead air)", mesh.audioEnabled)
        assertEquals("disableAudio must NOT be called while LiveKit is still connecting", 0, mesh.disableAudioCount)

        // Now LiveKit successfully reconnects
        lkPort.connectionStateFlow.value = ConnectionState.CONNECTED
        testScheduler.runCurrent()

        // LiveKit claims ownership and mesh playback stops ONLY NOW
        val recoverResult = coordinator.recoverToLiveKit()
        assertEquals(true, recoverResult)
        assertEquals("LiveKit owns audio after recovery", AudioOwner.LIVEKIT, arbiter.owner.value)
        assertFalse("Mesh audio is disabled only after LiveKit connects", mesh.audioEnabled)
        assertEquals(1, mesh.disableAudioCount)
    }

    @Test
    fun recovery_noDeadAir() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val lkPort = TestLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, dispatcher = testDispatcher)
        val mesh = TestMeshTransport()

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.OFFLINE_MESH, VoiceSession.Offline(OfflineSession.create(1000L)))
        arbiter.tryClaimOfflineMesh()
        mesh.enableAudio()

        // Connecting -> refused without affecting mesh audio
        lkPort.connectionStateFlow.value = ConnectionState.CONNECTING
        val preResult = coordinator.recoverToLiveKit()
        assertFalse(preResult)
        assertTrue("No dead air: mesh audio remains active while connecting", mesh.audioEnabled)

        // Connected -> recovers cleanly
        lkPort.connectionStateFlow.value = ConnectionState.CONNECTED
        testScheduler.runCurrent()
        assertTrue(coordinator.recoverToLiveKit())
        assertFalse("Mesh playback disables only after LiveKit connects", mesh.audioEnabled)
    }

    @Test
    fun recovery_timeout_keepsMeshCapture() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val lkPort = TestLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, dispatcher = testDispatcher)
        val mesh = TestMeshTransport()

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.OFFLINE_MESH, VoiceSession.Offline(OfflineSession.create(1000L)))
        arbiter.tryClaimOfflineMesh()
        mesh.enableAudio()

        // LiveKit stays in RECONNECTING forever -> recoverToLiveKit returns false, mesh remains active
        lkPort.connectionStateFlow.value = ConnectionState.RECONNECTING
        val recoverResult = coordinator.recoverToLiveKit()
        assertFalse("Recovery fails when LiveKit is not connected", recoverResult)
        assertFalse("Mesh capture remains active", mesh.capturePaused)
        assertTrue("Mesh audio remains active", mesh.audioEnabled)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
    }

    @Test
    fun micToggledOnMesh_survivesRecoveryAttemptThatFails() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val lkPort = TestLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, dispatcher = testDispatcher)
        val mesh = TestMeshTransport()

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.OFFLINE_MESH, VoiceSession.Offline(OfflineSession.create(1000L)))
        arbiter.tryClaimOfflineMesh()
        mesh.enableAudio()

        // User toggles mic on while on mesh
        mesh.setMicrophoneEnabled(true)
        assertEquals(MicState.ON, mesh.micState.value)

        // LiveKit stays in RECONNECTING forever -> recoverToLiveKit returns false immediately
        lkPort.connectionStateFlow.value = ConnectionState.RECONNECTING
        val recoverResult = coordinator.recoverToLiveKit()

        assertEquals("Recovery must fail when LiveKit is not connected", false, recoverResult)
        assertFalse("Mesh capture must remain active", mesh.capturePaused)
        assertEquals("Mic state toggled on mesh must survive failed recovery attempt", MicState.ON, mesh.micState.value)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
    }

    @Test
    fun hardwareBusy_neverDisconnects() = runTest {
        val lkPort = TestLiveKitPort()
        lkPort.isUserMutedFlow.value = true
        lkPort.isVoxRunningState = false

        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(lkPort, arbiter)
        val mesh = TestMeshTransport()
        mesh.failOnEnableAudio = true // Fails with HARDWARE_BUSY

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = NullOfflineSecretProvider(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        coordinator.setOnlineSession(VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))
        arbiter.claimLiveKit()

        val success = coordinator.fallbackToMesh()
        assertFalse("fallbackToMesh must return false on HARDWARE_BUSY", success)

        // Decision A9: NEVER disconnect/stop LiveKit from the mesh side
        assertEquals("LiveKit disconnectCount must be 0", 0, lkPort.disconnectCount)
        assertEquals("LiveKit owns audio after rollback", AudioOwner.LIVEKIT, arbiter.owner.value)
    }

    @Test
    fun quietGuard_requiresMuteAndVoxStopped() = runTest {
        val lkPort = TestLiveKitPort()
        // Not quiet: unmuted and VOX running
        lkPort.isUserMutedFlow.value = false
        lkPort.isVoxRunningState = true

        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(lkPort, arbiter)
        val mesh = TestMeshTransport()

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        coordinator.setOnlineSession(VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))
        arbiter.claimLiveKit()

        val success = coordinator.fallbackToMesh()
        assertFalse("fallbackToMesh must abort when LiveKit is not quiet", success)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)
    }

    @Test
    fun handing_back_timeout_transitions() = runTest {
        var virtualTime = 1_000L
        val sm = AutomaticSwitchStateMachine(
            config = AntiFlappingConfig(),
            clock = { virtualTime },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                // Hangs to cause timeout
                delay(30_000L)
                true
            }
        )

        sm.beginSession("room1", virtualTime)

        // Force to OFFLINE_ACTIVE
        val totalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(totalLoss) // ONLINE -> ONLINE_SUSPECT
        virtualTime += 15_000L
        sm.evaluate(totalLoss) // SUSPECT -> OFFLINE_ACTIVE
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Satisfy dwell and trigger recovery
        virtualTime += 25_000L
        val recoverable = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(recoverable) // OFFLINE_ACTIVE -> ONLINE_RECOVERING
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        virtualTime += 10_000L
        launch {
            sm.evaluate(recoverable) // ONLINE_RECOVERING -> HANDING_BACK_TO_LIVEKIT -> withTimeout(20_000)
        }
        testScheduler.runCurrent()

        assertEquals(AutoSwitchState.HANDING_BACK_TO_LIVEKIT, sm.state.value)

        // Advance 21 seconds to trigger timeout
        virtualTime += 21_000L
        testScheduler.advanceTimeBy(21_000L)

        assertEquals("Timed out handing back transitions to OFFLINE_ACTIVE with backoff", AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun starting_timeout_transitions() = runTest {
        var virtualTime = 1_000L
        val sm = AutomaticSwitchStateMachine(
            config = AntiFlappingConfig(),
            clock = { virtualTime },
            onHandoffToOffline = {
                // Hangs to cause timeout
                delay(30_000L)
                true
            },
            onHandoffToOnline = { true }
        )

        sm.beginSession("room1", virtualTime)

        val totalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(totalLoss)
        virtualTime += 15_000L

        launch {
            sm.evaluate(totalLoss) // Transitions to STARTING_OFFLINE_MESH -> withTimeout(20_000)
        }
        testScheduler.runCurrent()

        assertEquals(AutoSwitchState.STARTING_OFFLINE_MESH, sm.state.value)

        // Advance 21s to trigger timeout
        virtualTime += 21_000L
        testScheduler.advanceTimeBy(21_000L)

        assertEquals("Timed out starting mesh transitions to FALLBACK_UNAVAILABLE", AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
    }

    @Test
    fun endSession_duringHandoff_returnsPromptly() = runTest {
        var virtualTime = 1_000L
        val sm = AutomaticSwitchStateMachine(
            config = AntiFlappingConfig(),
            clock = { virtualTime },
            onHandoffToOffline = {
                delay(30_000L)
                true
            },
            onHandoffToOnline = { true }
        )

        sm.beginSession("room1", virtualTime)
        val totalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(totalLoss)
        virtualTime += 15_000L

        launch {
            sm.evaluate(totalLoss) // In flight
        }
        testScheduler.runCurrent()
        assertEquals(AutoSwitchState.STARTING_OFFLINE_MESH, sm.state.value)

        // End session while handoff is running
        sm.endSession()
        assertEquals(AutoSwitchState.SESSION_ENDED, sm.state.value)

        // Advance time
        virtualTime += 35_000L
        testScheduler.advanceTimeBy(35_000L)

        assertEquals("Session remains ended", AutoSwitchState.SESSION_ENDED, sm.state.value)
    }

    @Test
    fun offlineActive_meshDied_handsBack() = runTest {
        var virtualTime = 1_000L
        var handedBack = false
        val sm = AutomaticSwitchStateMachine(
            config = AntiFlappingConfig(),
            clock = { virtualTime },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                handedBack = true
                true
            }
        )

        sm.beginSession("room1", virtualTime)
        val totalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(totalLoss)
        virtualTime += 15_000L
        sm.evaluate(totalLoss)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // While in dwell, mesh transport dies (isMeshTransportAlive = false) and internet recovers
        val meshDeadSignals = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = false,
            offlineAvailable = false,
            isMeshTransportAlive = false // Mesh died!
        )

        sm.evaluate(meshDeadSignals)
        testScheduler.runCurrent()

        assertTrue("Mesh dying while internet recoverable must trigger immediate handback", handedBack)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }
}


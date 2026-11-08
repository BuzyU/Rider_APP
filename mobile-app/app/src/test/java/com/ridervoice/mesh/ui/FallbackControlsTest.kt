package com.ridervoice.mesh.ui

import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.audio.MicControllerImpl
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackControlsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val micScope = CoroutineScope(SupervisorJob() + testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        micScope.cancel()
        Dispatchers.resetMain()
    }

    private class FakeLiveKitPort : LiveKitPort {
        val connState = MutableStateFlow(ConnectionState.CONNECTED)
        override val connectionState: StateFlow<ConnectionState> = connState.asStateFlow()

        val participantsFlow = MutableStateFlow<List<Participant>>(emptyList())
        override val participants: StateFlow<List<Participant>> = participantsFlow.asStateFlow()

        val activeSpeakerFlow = MutableStateFlow<String?>(null)
        override val activeSpeaker: StateFlow<String?> = activeSpeakerFlow.asStateFlow()

        val mutedFlow = MutableStateFlow(false)
        override val isUserMuted: StateFlow<Boolean> = mutedFlow.asStateFlow()

        val deafenedFlow = MutableStateFlow(false)
        override val deafened: StateFlow<Boolean> = deafenedFlow.asStateFlow()

        var voxRunning = false
        override val isVoxRunning: Boolean get() = voxRunning

        val callLog = mutableListOf<String>()

        override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {
            callLog += "connect"
        }

        override fun disconnect() {
            callLog += "disconnect"
        }

        override fun setUserMuted(muted: Boolean) {
            callLog += "setUserMuted($muted)"
            mutedFlow.value = muted
        }

        override fun setDeafened(deafened: Boolean) {
            callLog += "setDeafened($deafened)"
            deafenedFlow.value = deafened
        }

        override fun onVoiceGate(open: Boolean) {
            callLog += "onVoiceGate($open)"
        }

        override fun stopVox() {
            callLog += "stopVox"
            voxRunning = false
        }

        override fun restartVoxIfPreviouslyRunning() {
            callLog += "restartVox"
            voxRunning = true
        }

        override fun onNetworkRestored() {
            callLog += "onNetworkRestored"
            connState.value = ConnectionState.CONNECTED
        }
    }

    private class FakeMeshVoiceTransport(
        val arbiter: AudioOwnershipArbiter,
        scope: CoroutineScope
    ) : VoiceTransport, MeshControl {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = arbiter.owner

        private val _micState = MutableStateFlow(MicState.OFF)
        override val micState: StateFlow<MicState> = _micState.asStateFlow()

        val _hasValidRoute = MutableStateFlow(true)
        override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

        var startCaptureCalls = 0
        var stopCaptureCalls = 0

        override val micController: MicController = MicControllerImpl(
            audioEnabled = { arbiter.owner.value == AudioOwner.OFFLINE_MESH },
            applyCapture = { wantCapture ->
                if (wantCapture) {
                    startCaptureCalls++
                    _micState.value = MicState.ON
                } else {
                    stopCaptureCalls++
                    _micState.value = MicState.OFF
                }
            },
            scope = scope
        )

        var audioEnabled = false
        var capturePaused = false

        override suspend fun startDiscoveryOnly(session: OfflineSession) {
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun enableAudio(): EnableAudioResult {
            audioEnabled = true
            capturePaused = false
            return EnableAudioResult.Success
        }

        override suspend fun disableAudio() {
            audioEnabled = false
            capturePaused = false
            stopCaptureCalls++
            _micState.value = MicState.OFF
        }

        override suspend fun pauseCaptureOnly() {
            capturePaused = true
            stopCaptureCalls++
            _micState.value = MicState.MUTED
        }

        override suspend fun resumeCaptureOnly() {
            capturePaused = false
            if (micController.micToggleOn.value || micController.pttHeld.value || micController.voxEnabled.value) {
                startCaptureCalls++
                _micState.value = MicState.ON
            } else {
                _micState.value = MicState.OFF
            }
        }

        override suspend fun start(session: VoiceSession) {
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun stop() {
            disableAudio()
            micController.close()
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            micController.setMicToggled(enabled)
        }

        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
        override suspend fun pause() { pauseCaptureOnly() }
        override suspend fun resume() { resumeCaptureOnly() }
    }

    private class FakeSecretProvider(var session: OfflineSession? = null) : OfflineSecretProvider {
        private val _flow = MutableStateFlow(session)
        override val backupSession: StateFlow<OfflineSession?> = _flow.asStateFlow()
    }

    @Test
    fun hostState_overlayActiveStatesMatchPlanSpecification() {
        val overlayActiveStates = setOf(
            AutoSwitchState.STARTING_OFFLINE_MESH,
            AutoSwitchState.OFFLINE_ACTIVE,
            AutoSwitchState.ONLINE_RECOVERING,
            AutoSwitchState.HANDING_BACK_TO_LIVEKIT
        )

        for (state in AutoSwitchState.values()) {
            val shouldBeActive = state in overlayActiveStates
            when (state) {
                AutoSwitchState.STARTING_OFFLINE_MESH,
                AutoSwitchState.OFFLINE_ACTIVE,
                AutoSwitchState.ONLINE_RECOVERING,
                AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> assertTrue("State $state must activate overlay", shouldBeActive)
                AutoSwitchState.ONLINE,
                AutoSwitchState.ONLINE_SUSPECT,
                AutoSwitchState.FALLBACK_UNAVAILABLE,
                AutoSwitchState.SESSION_ENDED -> assertFalse("State $state must NOT activate overlay", shouldBeActive)
            }
        }
    }

    @Test
    fun offlineActive_overlayPttDrivesOfflineCapture_notLiveKit() = runTest {
        val lkPort = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-1", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback to mesh must succeed", fellBack)
        testScheduler.runCurrent()

        lkPort.callLog.clear()
        val initialStartCalls = mesh.startCaptureCalls

        // User presses PTT on the overlay
        mesh.micController.setPttPressed(true)
        testScheduler.runCurrent()

        assertTrue("Pressing overlay PTT must drive offline capture start", mesh.startCaptureCalls > initialStartCalls)
        assertFalse("Pressing overlay PTT must NOT call LiveKit setUserMuted", lkPort.callLog.contains("setUserMuted(false)"))

        // User releases PTT on overlay
        mesh.micController.setPttPressed(false)
        testScheduler.runCurrent()

        assertTrue("Releasing overlay PTT must stop recording", mesh.stopCaptureCalls > 0)
        assertFalse("Releasing overlay PTT must NOT call LiveKit setUserMuted", lkPort.callLog.contains("setUserMuted(false)"))
    }

    @Test
    fun offlineActive_overlayMicToggleDrivesOfflineCapture_notLiveKit() = runTest {
        val lkPort = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-2", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback to mesh must succeed", fellBack)
        testScheduler.runCurrent()

        lkPort.callLog.clear()
        val initialStartCalls = mesh.startCaptureCalls

        // User toggles mic on via overlay
        mesh.micController.setMicToggled(true)
        testScheduler.runCurrent()

        assertTrue("Toggling mic on overlay must start recording", mesh.startCaptureCalls > initialStartCalls)
        assertFalse("Toggling mic on overlay must NOT touch LiveKit", lkPort.callLog.contains("setUserMuted(false)"))
        assertEquals(MicState.ON, mesh.micState.value)

        // User toggles mic off via overlay
        mesh.micController.setMicToggled(false)
        testScheduler.runCurrent()

        assertTrue("Toggling mic off on overlay must call stop", mesh.stopCaptureCalls > 0)
        assertEquals(MicState.OFF, mesh.micState.value)
    }

    @Test
    fun voxCarryOver_activeLiveKitVox_enablesMeshVoxOnFallback() = runTest {
        val lkPort = FakeLiveKitPort()
        lkPort.voxRunning = true // LiveKit VOX is currently active
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-3", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        assertFalse("Mesh VOX initially off", mesh.micController.voxEnabled.value)

        // Trigger fallback
        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback must succeed", fellBack)
        testScheduler.runCurrent()

        assertTrue("VOX active in LiveKit must carry over to mesh", mesh.micController.voxEnabled.value)
    }

    @Test
    fun voxCarryOver_inactiveLiveKitVox_doesNotEnableMeshVoxOnFallback() = runTest {
        val lkPort = FakeLiveKitPort()
        lkPort.voxRunning = false // LiveKit VOX is inactive
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-4", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        // Trigger fallback
        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback must succeed", fellBack)
        testScheduler.runCurrent()

        assertFalse("Inactive LiveKit VOX must NOT enable mesh VOX", mesh.micController.voxEnabled.value)
    }

    @Test
    fun micCarryOver_meshToLiveKit_onRecovery() = runTest {
        val lkPort = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-5", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback must succeed", fellBack)
        testScheduler.runCurrent()

        // User un-mutes on mesh
        mesh.micController.setMicToggled(true)
        testScheduler.runCurrent()
        assertEquals(MicState.ON, mesh.micState.value)

        // LiveKit reconnects
        lkPort.connState.value = ConnectionState.CONNECTED

        // Recovery to LiveKit
        val recovered = coordinator.recoverToLiveKit()
        assertTrue("Recovery must succeed", recovered)
        testScheduler.runCurrent()

        assertEquals("LiveKit mic state must be ON after recovery with mesh mic unmuted", MicState.ON, liveKit.micState.value)
        assertFalse("LiveKit port must be unmuted after recovery", lkPort.isUserMuted.value)
    }

    @Test
    fun micCarryOver_meshMutedToLiveKit_onRecovery() = runTest {
        val lkPort = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(liveKitPort = lkPort, audioOwnershipArbiter = arbiter, gateAllowed = { true }, dispatcher = testDispatcher)

        val mesh = FakeMeshVoiceTransport(arbiter, micScope)

        val session = OfflineSession.create(100_000L)
        val secretProvider = FakeSecretProvider(session)

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = secretProvider,
            clock = { testScheduler.currentTime },
            dispatcher = testDispatcher
        )

        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://test.io", "room-6", { "token" }))
        mesh.startDiscoveryOnly(session)
        testScheduler.runCurrent()

        val fellBack = coordinator.fallbackToMesh()
        assertTrue("Fallback must succeed", fellBack)
        testScheduler.runCurrent()

        // User mutes on mesh
        mesh.micController.setMicToggled(false)
        testScheduler.runCurrent()
        assertEquals(MicState.OFF, mesh.micState.value)

        // LiveKit reconnects
        lkPort.connState.value = ConnectionState.CONNECTED

        // Recovery to LiveKit
        val recovered = coordinator.recoverToLiveKit()
        assertTrue("Recovery must succeed", recovered)
        testScheduler.runCurrent()

        assertEquals("LiveKit mic state must be OFF after recovery with mesh mic muted", MicState.OFF, liveKit.micState.value)
        assertTrue("LiveKit port must be muted after recovery", lkPort.isUserMuted.value)
    }
}

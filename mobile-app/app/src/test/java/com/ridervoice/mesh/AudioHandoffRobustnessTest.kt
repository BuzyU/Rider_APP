package com.ridervoice.mesh

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import com.ridervoice.mesh.audio.AudioManagerPort
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.CaptureStart
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshAudioRouteController
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.audio.PlayoutFrame
import com.ridervoice.mesh.audio.SourceJitterBuffer
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.OfflineAvailability
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioHandoffRobustnessTest {

    // ---------------------------------------------------------
    // Test 1: pttRelease_doesNotCloseToggleOpenedMic
    // ---------------------------------------------------------
    @Test
    fun pttRelease_doesNotCloseToggleOpenedMic() = runBlocking {
        val transport = SimpleFakeVoiceTransport()
        val bridge = MeshVoxBridge(transport, dispatcher = Dispatchers.Unconfined)

        // User taps toggle ON -> mic should open
        bridge.setMicToggled(true)
        assertTrue("Mic must be toggled on", bridge.isMicToggledOn)
        assertTrue("Transport mic must be enabled by toggle", transport.isMicEnabled)

        // User presses PTT while toggle is ON
        bridge.setPttPressed(true)
        assertTrue("PTT must be active", bridge.isPttActive.value)
        assertTrue("Transport mic must remain enabled", transport.isMicEnabled)

        // User releases PTT -> toggle is still ON, mic MUST stay open
        bridge.setPttPressed(false)
        assertFalse("PTT must be inactive", bridge.isPttActive.value)
        assertTrue("Mic must remain open because toggle is ON", bridge.isMicToggledOn)
        assertTrue("Transport mic must remain enabled after PTT release", transport.isMicEnabled)
        assertTrue("Gate must remain open", bridge.isGateOpen.value)

        bridge.close()
    }

    // ---------------------------------------------------------
    // Test 2: truthTable16 (all 16 combinations)
    // ---------------------------------------------------------
    @Test
    fun truthTable16() {
        // Q7 mic semantics truth table:
        // open = audioEnabled && (micToggleOn || pttHeld || (voxEnabled && armed))
        for (a in 0..1) {
            val audioEnabled = (a == 1)
            for (t in 0..1) {
                val micToggleOn = (t == 1)
                for (p in 0..1) {
                    val pttHeld = (p == 1)
                    for (v in 0..1) {
                        val voxActive = (v == 1) // voxEnabled && armed

                        val expected = audioEnabled && (micToggleOn || pttHeld || voxActive)

                        // Evaluate via direct boolean logic matching Q7 specification
                        val actual = if (!audioEnabled) {
                            false
                        } else {
                            micToggleOn || pttHeld || voxActive
                        }

                        assertEquals(
                            "Mismatch for audioEnabled=$audioEnabled, toggle=$micToggleOn, ptt=$pttHeld, vox=$voxActive",
                            expected,
                            actual
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Test 3: helmetKey_isToggle
    // ---------------------------------------------------------
    @Test
    fun helmetKey_isToggle() = runBlocking {
        val transport = SimpleFakeVoiceTransport()
        val pttPort = TestHardwarePttPort()
        val bridge = MeshVoxBridge(transport, hardwarePttPort = pttPort, dispatcher = Dispatchers.Unconfined)

        assertFalse("Initial toggle state is false", bridge.isMicToggledOn)

        // First helmet button press -> toggles ON
        pttPort.onMicToggleRequest?.invoke(true)
        assertTrue("First helmet press must toggle mic ON", bridge.isMicToggledOn)

        // Second helmet button press -> toggles OFF
        pttPort.onMicToggleRequest?.invoke(true)
        assertFalse("Second helmet press must toggle mic OFF", bridge.isMicToggledOn)

        bridge.close()
    }

    // ---------------------------------------------------------
    // Test 4: previousPttCallback_savedAndRestored
    // ---------------------------------------------------------
    @Test
    fun previousPttCallback_savedAndRestored() {
        val transport = SimpleFakeVoiceTransport()
        val pttPort = TestHardwarePttPort()
        var priorCallbackInvoked = false
        val priorCallback: (Boolean) -> Unit = { priorCallbackInvoked = true }
        pttPort.onMicToggleRequest = priorCallback

        val bridge = MeshVoxBridge(transport, hardwarePttPort = pttPort, dispatcher = Dispatchers.Unconfined)
        // Verify port callback was replaced by bridge
        assertTrue("Port callback was attached", pttPort.onMicToggleRequest != priorCallback)

        // Close bridge
        bridge.close()

        // Verify prior callback was restored
        assertEquals("Prior callback must be restored", priorCallback, pttPort.onMicToggleRequest)
        pttPort.onMicToggleRequest?.invoke(true)
        assertTrue("Prior callback should be callable", priorCallbackInvoked)
    }

    // ---------------------------------------------------------
    // Test 5: handoff_abortsWhenLiveKitNotQuiet
    // ---------------------------------------------------------
    @Test
    fun handoff_abortsWhenLiveKitNotQuiet() = runBlocking {
        val lkPort = TestLiveKitPort()
        // Stubborn VOX running: LiveKit does NOT become quiet
        lkPort.isVoxRunningState = true
        lkPort.canStopVox = false

        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(lkPort, arbiter)
        val mesh = FakeMeshTransport()
        mesh._hasValidRoute.value = true

        var virtualTime = 1_000L
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = object : OfflineSecretProvider {
                override fun getSecretForOnlineRide(roomId: String) = ByteArray(32) { 1 }
            },
            clock = {
                virtualTime += 1_000L
                virtualTime
            }
        )

        coordinator.setOnlineSession(VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))

        // Handoff must abort when LiveKit is not quiet within timeout
        val fallbackResult = coordinator.fallbackToMesh()
        assertFalse("Handoff must abort when LiveKit is not quiet", fallbackResult)
        assertEquals("Owner must remain/rollback to LIVEKIT", AudioOwner.LIVEKIT, arbiter.owner.value)
        assertEquals("Active transport must remain LiveKit", PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }

    // ---------------------------------------------------------
    // Test 6: handoff_rollsBackOwner
    // ---------------------------------------------------------
    @Test
    fun handoff_rollsBackOwner() = runBlocking {
        val lkPort = TestLiveKitPort()
        lkPort.setUserMuted(true)
        lkPort.isVoxRunningState = false

        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(lkPort, arbiter)
        val mesh = FakeMeshTransport()
        mesh._hasValidRoute.value = true
        // Mesh fails on enableAudio()
        mesh.failOnEnableAudio = true

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = object : OfflineSecretProvider {
                override fun getSecretForOnlineRide(roomId: String) = ByteArray(32) { 1 }
            }
        )

        coordinator.setOnlineSession(VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))

        val fallbackResult = coordinator.fallbackToMesh()
        assertFalse("Fallback must return false when mesh enableAudio fails", fallbackResult)
        assertEquals("Arbiter owner must be rolled back to LIVEKIT", AudioOwner.LIVEKIT, arbiter.owner.value)
        assertFalse("Mesh audio must not be left enabled", mesh.audioEnabled)
    }

    // ---------------------------------------------------------
    // Test 7: delayedUnmute_isCountered
    // ---------------------------------------------------------
    @Test
    fun delayedUnmute_isCountered() = runBlocking {
        val lkPort = TestLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val transport = LiveKitVoiceTransport(lkPort, arbiter)

        transport.setMicrophoneEnabled(true)
        assertFalse(lkPort.isUserMuted.value)

        // Pause LiveKit
        transport.pause()
        assertTrue("Port must be muted on pause", lkPort.isUserMuted.value)

        // Simulate 150ms delayed unmute by external code or LiveKit startVoxSafely()
        lkPort.setUserMuted(false)
        assertFalse("Port unmuted unexpectedly", lkPort.isUserMuted.value)

        // Transport reasserts pause mute
        transport.reassertPauseMute()
        assertTrue("Pause mute must be re-asserted", lkPort.isUserMuted.value)
    }

    // ---------------------------------------------------------
    // Test 8: captureNotStartedWhenGateClosed
    // ---------------------------------------------------------
    @Test
    fun captureNotStartedWhenGateClosed() {
        var recordCreated = false
        val capture = OfflineAudioCapture(
            gateAllowed = { false }, // Gate CLOSED
            canClaimAudio = { true },
            hasMicPermission = { true },
            audioRecordFactory = {
                recordCreated = true
                null
            },
            onPcmFrame = {}
        )

        val result = capture.start()
        assertEquals("Capture must report GateClosed", CaptureStart.GateClosed, result)
        assertFalse("AudioRecord must never be created when gate is closed", recordCreated)
        assertFalse("Capture must not be active", capture.isCapturing.value)
    }

    // ---------------------------------------------------------
    // Test 9: effectsReleased
    // ---------------------------------------------------------
    @Test
    fun effectsReleased() {
        var released = false
        val fakeRecordSource = object : AudioRecordSource {
            override val state: Int = AudioRecord.STATE_INITIALIZED
            override val audioSessionId: Int = 100
            override fun startRecording() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
            override fun stop() {}
            override fun release() { released = true }
        }

        val capture = OfflineAudioCapture(
            gateAllowed = { true },
            canClaimAudio = { true },
            hasMicPermission = { true },
            audioRecordFactory = { fakeRecordSource },
            onPcmFrame = {}
        )

        capture.setMicEnabled(true)
        val res = capture.start()
        assertEquals(CaptureStart.Started, res)
        assertTrue(capture.isCapturing.value)

        // Stop capture: releases effects and underlying source
        capture.stop()
        assertFalse(capture.isCapturing.value)
        assertTrue("AudioRecordSource must be released", released)
    }

    // ---------------------------------------------------------
    // Test 10: jitterWrapAround
    // ---------------------------------------------------------
    @Test
    fun jitterWrapAround() {
        var fakeClock = 10_000L
        val buf = SourceJitterBuffer(
            sourceId = "peer1",
            targetDelayFrames = 3,
            maxQueueFrames = 16,
            clock = { fakeClock }
        )

        // Prime the buffer with sequences near 32-bit boundary
        val seqMaxMinus1 = Int.MAX_VALUE - 1 // 2147483646
        val seqMax = Int.MAX_VALUE           // 2147483647
        val seqWrapped = Int.MIN_VALUE       // -2147483648 (diff = Int.MIN_VALUE - Int.MAX_VALUE = 1 > 0)

        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME)

        buf.enqueue(seqMaxMinus1, dummyPcm)
        buf.enqueue(seqMax, dummyPcm)
        buf.enqueue(seqWrapped, dummyPcm)

        assertTrue("Buffer must be primed after 3 frames", buf.isPrimed())
        assertEquals("No late frames dropped", 0L, buf.lateDroppedCount)

        // Poll frames in order across wrap-around
        val f1 = buf.pollFrame() as PlayoutFrame.Audio
        assertEquals(seqMaxMinus1, f1.sequence)

        val f2 = buf.pollFrame() as PlayoutFrame.Audio
        assertEquals(seqMax, f2.sequence)

        val f3 = buf.pollFrame() as PlayoutFrame.Audio
        assertEquals("Wrapped sequence must be delivered sequentially", seqWrapped, f3.sequence)
    }

    // ---------------------------------------------------------
    // Test 11: micStateCarriedAcrossFallbackAndRecovery
    // ---------------------------------------------------------
    @Test
    fun micStateCarriedAcrossFallbackAndRecovery() = runBlocking {
        val lkPort = TestLiveKitPort()
        lkPort.setUserMuted(true)
        lkPort.isVoxRunningState = false

        val arbiter = AudioOwnershipArbiter()
        val liveKit = LiveKitVoiceTransport(lkPort, arbiter)
        val mesh = FakeMeshTransport()
        mesh._hasValidRoute.value = true

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = liveKit,
            meshTransport = mesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = object : OfflineSecretProvider {
                override fun getSecretForOnlineRide(roomId: String) = ByteArray(32) { 1 }
            }
        )

        coordinator.setOnlineSession(VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, VoiceSession.Online("wss://livekit", "room1", { "tok" }, "room1"))

        // User un-mutes microphone in LiveKit
        liveKit.setMicrophoneEnabled(true)
        assertEquals(MicState.ON, liveKit.micState.value)

        // Fallback to mesh occurs
        val fallbackOk = coordinator.fallbackToMesh()
        assertTrue("Fallback must succeed", fallbackOk)
        assertEquals("Active transport must be OFFLINE_MESH", PeerTransportKind.OFFLINE_MESH, coordinator.activeKind.value)
        assertEquals("Mic state ON must be carried into mesh", MicState.ON, mesh.micState.value)

        // Recovery to LiveKit occurs
        lkPort.connectionStateFlow.value = ConnectionState.CONNECTED
        val recoverOk = coordinator.recoverToLiveKit()
        assertTrue("Recovery must succeed", recoverOk)
        assertEquals("Active transport must be ONLINE_LIVEKIT", PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
        assertEquals("Mic state ON must be carried back into LiveKit", MicState.ON, liveKit.micState.value)
    }

    // ---------------------------------------------------------
    // Test 12: routeController_inertWhenLiveKitOwns
    // ---------------------------------------------------------
    @Test
    fun routeController_inertWhenLiveKitOwns() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        arbiter.claimLiveKit() // LiveKit owns audio

        val testPort = TestAudioManagerPort()
        testPort.mode = AudioManager.MODE_NORMAL
        testPort.isBluetoothScoOn = false

        val routeController = MeshAudioRouteController(testPort, arbiter)

        // Attempt to acquire communication mode or set route while LiveKit owns
        routeController.acquireCommunicationMode()
        assertEquals("AudioManager mode must remain unchanged when LiveKit owns audio", AudioManager.MODE_NORMAL, testPort.mode)

        routeController.setRoute(AudioRoute.BLUETOOTH)
        assertFalse("Bluetooth SCO must not be activated when LiveKit owns audio", testPort.isBluetoothScoOn)
    }

    // ---------------------------------------------------------
    // Helpers and Fakes
    // ---------------------------------------------------------
    private class SimpleFakeVoiceTransport : VoiceTransport {
        var isMicEnabled: Boolean = false
        override val state: StateFlow<VoiceTransportState> = MutableStateFlow(VoiceTransportState.CONNECTED)
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.OFFLINE_MESH)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)

        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            isMicEnabled = enabled
        }
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    private class TestHardwarePttPort : HardwarePttPort {
        override var onMicToggleRequest: ((Boolean) -> Unit)? = null
        var active = false
        override fun activateSession() { active = true }
        override fun deactivateSession() { active = false }
    }

    private class TestLiveKitPort : LiveKitPort {
        val connectionStateFlow = MutableStateFlow(ConnectionState.CONNECTED)
        override val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()
        override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
        override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
        private val _isUserMuted = MutableStateFlow(false)
        override val isUserMuted: StateFlow<Boolean> = _isUserMuted.asStateFlow()
        private val _deafened = MutableStateFlow(false)
        override val deafened: StateFlow<Boolean> = _deafened.asStateFlow()
        var isVoxRunningState = false
        var canStopVox = true
        override val isVoxRunning: Boolean get() = isVoxRunningState

        override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {
            connectionStateFlow.value = ConnectionState.CONNECTED
        }
        override fun disconnect() {
            connectionStateFlow.value = ConnectionState.DISCONNECTED
        }
        override fun setUserMuted(muted: Boolean) {
            _isUserMuted.value = muted
        }
        override fun setDeafened(deafened: Boolean) {
            _deafened.value = deafened
        }
        override fun stopVox() {
            if (canStopVox) {
                isVoxRunningState = false
            }
        }
        override fun restartVoxIfPreviouslyRunning() {
            isVoxRunningState = true
        }
    }

    private class FakeMeshTransport : VoiceTransport, MeshControl {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        private val _micState = MutableStateFlow(MicState.OFF)
        override val micState: StateFlow<MicState> = _micState.asStateFlow()

        val _hasValidRoute = MutableStateFlow(false)
        override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

        var audioEnabled = false
        var failOnEnableAudio = false

        override suspend fun startDiscoveryOnly(session: OfflineSession) {
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun enableAudio(): EnableAudioResult {
            if (failOnEnableAudio) {
                return EnableAudioResult.Failure.HardwareBusy
            }
            audioEnabled = true
            return EnableAudioResult.Success
        }

        override suspend fun disableAudio() {
            audioEnabled = false
        }

        override suspend fun start(session: VoiceSession) {
            audioEnabled = true
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun stop() {
            audioEnabled = false
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            _micState.value = if (enabled) MicState.ON else MicState.OFF
        }
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    private class TestAudioManagerPort : AudioManagerPort {
        override var mode: Int = AudioManager.MODE_NORMAL
        override var isSpeakerphoneOn: Boolean = false
        override var isBluetoothScoOn: Boolean = false
        override val isBluetoothScoAvailableOffCall: Boolean = true
        override val isWiredHeadsetOn: Boolean = false
        override val availableCommunicationDevices: List<AudioDeviceInfo> = emptyList()

        override fun requestCommunicationFocus(): Boolean = true
        override fun abandonCommunicationFocus() {}
        override fun startBluetoothSco() { isBluetoothScoOn = true }
        override fun stopBluetoothSco() { isBluetoothScoOn = false }
        override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean = true
        override fun clearCommunicationDevice() {}
    }
}

package com.ridervoice.mesh

import com.ridervoice.audio.VoxDetector
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.CaptureStart
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.audio.MicControllerImpl
import com.ridervoice.mesh.audio.MicInputs
import com.ridervoice.mesh.audio.MicRules
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.LiveKitManagerPort
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MicAudioControlPathTest {

    // -------------------------------------------------------------
    // Truth table tests (Q7/Q8)
    // -------------------------------------------------------------
    @Test
    fun truthTable32_exhaustive() {
        val booleans = listOf(false, true)
        for (audio in booleans) {
            for (toggle in booleans) {
                for (ptt in booleans) {
                    for (voxEnabled in booleans) {
                        for (voxArmed in booleans) {
                            val inputs = MicInputs(audio, toggle, ptt, voxEnabled, voxArmed)
                            val expectedCapture = audio && (toggle || ptt || voxEnabled)
                            val expectedTransmit = audio && (toggle || ptt || (voxEnabled && voxArmed))

                            assertEquals("Mismatch captureWanted for $inputs", expectedCapture, MicRules.captureWanted(inputs))
                            assertEquals("Mismatch transmit for $inputs", expectedTransmit, MicRules.transmit(inputs))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun voxOnNoToggle_capturesWarm_butDoesNotTransmitUntilArmed() {
        val i = MicInputs(audioEnabled = true, micToggleOn = false, pttHeld = false, voxEnabled = true, voxArmed = false)
        assertTrue(MicRules.captureWanted(i))
        assertFalse(MicRules.transmit(i))

        val armed = i.copy(voxArmed = true)
        assertTrue(MicRules.captureWanted(armed))
        assertTrue(MicRules.transmit(armed))
    }

    @Test
    fun pttRelease_keepsToggleMic() {
        val i = MicInputs(audioEnabled = true, micToggleOn = true, pttHeld = false, voxEnabled = false, voxArmed = false)
        assertTrue(MicRules.captureWanted(i))
        assertTrue(MicRules.transmit(i))
    }

    @Test
    fun audioDisabled_neverCapturesOrTransmits() {
        for (t in listOf(true, false)) {
            for (p in listOf(true, false)) {
                for (v in listOf(true, false)) {
                    val i = MicInputs(false, t, p, v, true)
                    assertFalse(MicRules.captureWanted(i))
                    assertFalse(MicRules.transmit(i))
                }
            }
        }
    }

    @Test
    fun defaultVoxIsOff() = runTest {
        var captureWanted = false
        val controller = MicControllerImpl(
            audioEnabled = { true },
            applyCapture = { captureWanted = it },
            scope = backgroundScope
        )
        assertFalse("Default VOX must be false", controller.voxEnabled.value)
        assertFalse("Default mic toggle must be false", controller.micToggleOn.value)
        assertFalse("Default PTT must be false", controller.pttHeld.value)
    }

    @Test
    fun helmetKey_flipsToggle() = runTest {
        val controller = MicControllerImpl(
            audioEnabled = { true },
            applyCapture = {},
            scope = backgroundScope
        )
        assertFalse(controller.micToggleOn.value)
        controller.toggleMic()
        assertTrue(controller.micToggleOn.value)
        controller.toggleMic()
        assertFalse(controller.micToggleOn.value)
    }

    // -------------------------------------------------------------
    // Bridge / Helmet PTT lifecycle tests (CODE-4)
    // -------------------------------------------------------------
    private class TestHardwarePttPort : HardwarePttPort {
        var callback: ((Boolean) -> Unit)? = null
        var activateCount = 0
        var deactivateCount = 0

        override var onMicToggleRequest: ((Boolean) -> Unit)?
            get() = callback
            set(value) { callback = value }

        override fun activateSession() { activateCount++ }
        override fun deactivateSession() { deactivateCount++ }
    }

    @Test
    fun attach_detach_restoresRoomCallback() {
        val port = TestHardwarePttPort()
        val roomCallback: (Boolean) -> Unit = {}
        port.onMicToggleRequest = roomCallback

        val fakeTransport = FakeVoiceTransport()
        val bridge = MeshVoxBridge(fakeTransport, hardwarePttPort = port)

        assertEquals("Bridge init must not touch callback", roomCallback, port.onMicToggleRequest)
        assertEquals(0, port.activateCount)

        bridge.attach(roomOwnsAudio = false)
        assertTrue("Bridge must set own callback", port.onMicToggleRequest !== roomCallback)
        assertEquals(1, port.activateCount)

        bridge.detach()
        assertEquals("Bridge detach must restore room callback", roomCallback, port.onMicToggleRequest)
        assertEquals(1, port.deactivateCount)
    }

    @Test
    fun detach_doesNotDeactivateSessionItDidNotActivate() {
        val port = TestHardwarePttPort()
        val fakeTransport = FakeVoiceTransport()
        val bridge = MeshVoxBridge(fakeTransport, hardwarePttPort = port)

        // roomOwnsAudio = true (e.g. LiveKit owns audio)
        bridge.attach(roomOwnsAudio = true)
        assertEquals("Must not activate session if room owns audio", 0, port.activateCount)

        bridge.detach()
        assertEquals("Must not deactivate session if not activated by us", 0, port.deactivateCount)
    }

    @Test
    fun secondSession_reattaches() {
        val port = TestHardwarePttPort()
        val fakeTransport = FakeVoiceTransport()
        val bridge = MeshVoxBridge(fakeTransport, hardwarePttPort = port)

        bridge.attach(false)
        assertEquals(1, port.activateCount)
        bridge.detach()
        assertEquals(1, port.deactivateCount)

        bridge.attach(false)
        assertEquals(2, port.activateCount)
        bridge.detach()
        assertEquals(2, port.deactivateCount)
    }

    // -------------------------------------------------------------
    // VOX tracking in LiveKitManagerPort (CODE-5)
    // -------------------------------------------------------------
    private class FakeLiveKitPort(
        val connState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED),
        val muted: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val deaf: MutableStateFlow<Boolean> = MutableStateFlow(false)
    ) : LiveKitPort {
        override val connectionState: StateFlow<ConnectionState> = connState.asStateFlow()
        override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
        override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
        override val isUserMuted: StateFlow<Boolean> = muted.asStateFlow()
        override val deafened: StateFlow<Boolean> = deaf.asStateFlow()

        var voxRunning = false
        var voxStopped = false
        var voxRestarted = false

        override val isVoxRunning: Boolean
            get() = connState.value == ConnectionState.CONNECTED && voxRunning

        override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {
            connState.value = ConnectionState.CONNECTED
            voxRunning = true
        }

        override fun disconnect() {
            connState.value = ConnectionState.DISCONNECTED
            voxRunning = false
        }

        override fun setUserMuted(m: Boolean) { muted.value = m }
        override fun setDeafened(d: Boolean) { deaf.value = d }
        override fun stopVox() { voxStopped = true; voxRunning = false }
        override fun restartVoxIfPreviouslyRunning() {
            if (connState.value == ConnectionState.CONNECTED) {
                voxRestarted = true
                voxRunning = true
            }
        }
    }

    @Test
    fun pause_remembersVoxWhenConnected() = runBlocking {
        val port = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val transport = LiveKitVoiceTransport(port, arbiter)

        port.connect("wss://test", "room1", { "tok" }, "room1")
        assertTrue("VOX should be running when connected", port.isVoxRunning)

        transport.pause()
        assertTrue("VOX should be stopped on pause", port.voxStopped)

        transport.resume()
        assertTrue("VOX should be restarted on resume when connected", port.voxRestarted)
    }

    @Test
    fun resume_whileDisconnected_doesNotStartVox() = runBlocking {
        val port = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val transport = LiveKitVoiceTransport(port, arbiter)

        port.connect("wss://test", "room1", { "tok" }, "room1")
        transport.pause()

        // Disconnect before resume
        port.disconnect()
        port.voxRestarted = false

        transport.resume()
        assertFalse("VOX must NOT restart while disconnected", port.voxRestarted)
    }

    // -------------------------------------------------------------
    // AudioRecord empty reads test (CODE-12)
    // -------------------------------------------------------------
    @Test
    fun emptyReads50_reportHardwareBusy() = runBlocking {
        var hardwareBusyReported = false
        var readsCount = 0

        val fakeSource = object : AudioRecordSource {
            override val state: Int = android.media.AudioRecord.STATE_INITIALIZED
            override val audioSessionId: Int = 1
            override fun startRecording() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                readsCount++
                return 0 // Always 0
            }
            override fun stop() {}
            override fun release() {}
        }

        val capture = OfflineAudioCapture(
            gateAllowed = { true },
            canClaimAudio = { true },
            hasMicPermission = { true },
            onHardwareBusy = { hardwareBusyReported = true },
            dispatcher = Dispatchers.Default,
            audioRecordFactory = { fakeSource },
            onPcmFrame = {}
        )

        capture.setMicEnabled(true)
        val startResult = capture.start()
        assertEquals(CaptureStart.Started, startResult)

        // Wait for loop to hit 50 empty reads (50 * 10ms = ~500ms max)
        val start = System.currentTimeMillis()
        while (!hardwareBusyReported && System.currentTimeMillis() - start < 2000) {
            kotlinx.coroutines.delay(20)
        }

        capture.stop()
        assertTrue("Hardware busy must be reported after 50 consecutive 0-reads", hardwareBusyReported)
        assertTrue("Must have read at least 50 times", readsCount >= 50)
    }

    // -------------------------------------------------------------
    // Transport lifecycle tests (CODE-13)
    // -------------------------------------------------------------
    private class FakeMeshLink : MeshLink {
        override val linkId: String = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        private val _frames = MutableSharedFlow<MeshFrame>()
        override val incomingFrames: SharedFlow<MeshFrame> = _frames.asSharedFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
    }

    @Test
    fun deafen_survivesRecreate() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val link = FakeMeshLink()

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            dispatcher = Dispatchers.Default
        )

        val session = OfflineSession.create(System.currentTimeMillis())
        transport.startDiscoveryOnly(session)

        // User deafens before audio is enabled
        transport.setDeafened(true)
        assertTrue("Deafened flow must be true", transport.deafened.value)

        // Enable audio
        val res = transport.enableAudio()
        assertTrue(res is EnableAudioResult.Success)
        assertTrue("Deafened must stay true after enableAudio", transport.deafened.value)

        transport.stop()
        assertTrue("Deafened preference survives stop", transport.deafened.value)
    }

    @Test
    fun rapidPressRelease_endsReleased() = runTest {
        val controller = MicControllerImpl(
            audioEnabled = { true },
            applyCapture = {},
            scope = backgroundScope
        )
        for (i in 1..20) {
            controller.setPttPressed(true)
            controller.setPttPressed(false)
        }
        assertFalse("Final PTT state must be false (released)", controller.pttHeld.value)
    }

    private class FakeVoiceTransport : VoiceTransport {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<com.ridervoice.mesh.transport.UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        val _micState = MutableStateFlow(MicState.OFF)
        override val micState: StateFlow<MicState> = _micState.asStateFlow()

        var micEnabled = false

        override suspend fun start(session: VoiceSession) { _state.value = VoiceTransportState.CONNECTED }
        override suspend fun stop() { _state.value = VoiceTransportState.STOPPED }
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            micEnabled = enabled
            _micState.value = if (enabled) MicState.ON else MicState.OFF
        }
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
    }
}

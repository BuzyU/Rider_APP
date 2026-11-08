package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.AudioTrackSink
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.ui.OfflineAwareRoomViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MicCaptureViaControllerTest {

    private class CountingAudioRecordSource : AudioRecordSource {
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        private val isStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        override val state: Int = 1
        override val audioSessionId: Int = 101
        override fun startRecording() {
            if (isStarted.compareAndSet(false, true)) {
                startCount.incrementAndGet()
            }
        }
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
        override fun stop() {
            if (isStarted.compareAndSet(true, false)) {
                stopCount.incrementAndGet()
            }
        }
        override fun release() {}
    }

    private class FakeAudioTrackSink : AudioTrackSink {
        override val state: Int = 1
        override fun play() {}
        override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
        override fun stop() {}
        override fun release() {}
    }

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

    private class FakeHardwarePttPort : HardwarePttPort {
        override var onMicToggleRequest: ((Boolean) -> Unit)? = null
        override var isSessionActive: Boolean = false
        override fun activateSession() { isSessionActive = true }
        override fun deactivateSession() { isSessionActive = false }
    }

    private class FakeRuntimeSettings(initial: Boolean = true) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = initial,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )
        override val settingsFlow = _flow.asStateFlow()
        override val offlineMeshEnabled get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(offlineMeshEnabled = v)
        }
        override fun setAutomaticFallbackEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(automaticFallbackEnabled = v)
        }
        override fun setWarmStandbyEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(warmStandbyEnabled = v)
        }
    }

    private val lastSource = MutableStateFlow<CountingAudioRecordSource?>(null)
    private val createdTransports = mutableListOf<OfflineMeshVoiceTransport>()

    @Before
    fun setUp() {
        FeatureFlags.localOverride = true
        OfflineMeshGate.install(FakeRuntimeSettings(true))
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        createdTransports.forEach {
            runCatching {
                runBlocking { it.stop() }
                it.close()
            }
        }
        createdTransports.clear()
    }

    private fun createTransport(
        pttPort: HardwarePttPort? = null,
        arbiter: AudioOwnershipArbiter = AudioOwnershipArbiter()
    ): OfflineMeshVoiceTransport {
        val identity = MeshIdentity("00000000-0000-0000-0000-000000000001", "tester-01")
        val link = FakeMeshLink()
        val t = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            audioRecordFactory = {
                CountingAudioRecordSource().also { lastSource.value = it }
            },
            audioTrackFactory = { FakeAudioTrackSink() },
            hardwarePttPort = pttPort,
            dispatcher = Dispatchers.Default
        )
        createdTransports.add(t)
        return t
    }

    @Test
    fun pttPressStartsCaptureAndReleaseStopsCaptureOnRealTransport() = runBlocking {
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        assertEquals("Precondition: mic toggle off and vox off should leave micState OFF", MicState.OFF, transport.micState.value)

        // Subtest (a): PTT press starts capture -> ON; release -> OFF
        transport.micController.setPttPressed(true)
        val onState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.ON }
        }
        assertEquals("PTT press should change micState to ON", MicState.ON, onState)
        assertNotNull("AudioRecordSource should be created", lastSource.value)
        assertEquals("startRecording called once", 1, lastSource.value?.startCount?.get() ?: 0)

        transport.micController.setPttPressed(false)
        val offState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.OFF }
        }
        assertEquals("PTT release should change micState to OFF", MicState.OFF, offState)
        assertEquals("stop called once", 1, lastSource.value?.stopCount?.get() ?: 0)

        transport.stop()
    }

    @Test
    fun toggleMicTurnsCaptureOnAndOff() = runBlocking {
        // Subtest (b): micController.toggleMic() twice
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        transport.micController.toggleMic()
        val onState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.ON }
        }
        assertEquals("Toggle on should change micState to ON", MicState.ON, onState)

        transport.micController.toggleMic()
        val offState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.OFF }
        }
        assertEquals("Toggle off should change micState to OFF", MicState.OFF, offState)

        transport.stop()
    }

    @Test
    fun hardwarePttOpensAndClosesCapture() = runBlocking {
        // Subtest (c): hardware-PTT callback (HardwarePttPort.onMicToggleRequest(true)) opens/closes
        val pttPort = FakeHardwarePttPort()
        val transport = createTransport(pttPort = pttPort)
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        assertNotNull("Hardware PTT callback should be attached", pttPort.onMicToggleRequest)

        // First press: opens capture (toggles ON)
        pttPort.onMicToggleRequest?.invoke(true)
        val onState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.ON }
        }
        assertEquals("Hardware PTT toggle on should open mic", MicState.ON, onState)

        // Second press: closes capture (toggles OFF)
        pttPort.onMicToggleRequest?.invoke(true)
        val offState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.OFF }
        }
        assertEquals("Hardware PTT toggle off should close mic", MicState.OFF, offState)

        transport.stop()
    }

    @Test
    fun setMicrophoneEnabledThenPttReleaseKeepsCaptureOn() = runBlocking {
        // Subtest (d): setMicrophoneEnabled(true) then PTT release keeps capture on
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        transport.setMicrophoneEnabled(true)
        val onState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.ON }
        }
        assertEquals("setMicrophoneEnabled(true) should open mic", MicState.ON, onState)

        // Press PTT while toggle is already on
        transport.micController.setPttPressed(true)
        // Release PTT: capture must stay ON because toggle is on
        transport.micController.setPttPressed(false)

        kotlinx.coroutines.delay(100L)
        assertEquals("PTT release when micToggleOn is true must keep micState ON", MicState.ON, transport.micState.value)

        transport.stop()
    }

    @Test
    fun whilePausedNothingStartsAndStateIsMuted() = runBlocking {
        // Subtest (e): while paused nothing starts and state is MUTED
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        transport.pause()
        assertEquals("Pause should set micState to MUTED", MicState.MUTED, transport.micState.value)

        val srcBefore = lastSource.value?.startCount?.get() ?: 0
        transport.micController.setPttPressed(true)

        kotlinx.coroutines.delay(100L)
        assertEquals("While paused, micState must remain MUTED", MicState.MUTED, transport.micState.value)
        assertEquals("No new capture start should occur while paused", srcBefore, lastSource.value?.startCount?.get() ?: 0)

        transport.micController.setPttPressed(false)
        transport.stop()
    }

    @Test
    fun rapidPressReleaseEndsReleasedWithBalancedStartStop() = runBlocking {
        // Subtest (f): rapid press/release x50 ends released with exactly balanced start/stop
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        for (i in 1..50) {
            transport.micController.setPttPressed(true)
            transport.micController.setPttPressed(false)
        }

        val finalState = withTimeoutOrNull(2000L) {
            transport.micState.first { it == MicState.OFF }
        }
        assertEquals("Rapid press/release should settle to OFF", MicState.OFF, finalState)
        assertFalse("Final PTT held state must be false", transport.micController.pttHeld.value)

        val starts = lastSource.value?.startCount?.get() ?: 0
        val stops = lastSource.value?.stopCount?.get() ?: 0
        assertEquals("Start and stop calls on AudioRecordSource must be balanced", starts, stops)

        transport.stop()
    }

    @Test
    fun overlayPathViaOfflineAwareRoomViewModel() = runBlocking {
        // Subtest (g): test through OfflineAwareRoomViewModel so the overlay path is covered
        val transport = createTransport()
        val session = OfflineSession.create(now = System.currentTimeMillis())
        transport.start(VoiceSession.Offline(session))

        val viewModel = OfflineAwareRoomViewModel(
            lazyAutoSwitchManager = dagger.Lazy { error("Not needed for micController tests") },
            lazyCoordinator = dagger.Lazy { error("Not needed for micController tests") },
            lazyMeshTransport = dagger.Lazy { transport }
        )

        // Overlay toggle path (OfflineAwareRoomHost.kt line 180)
        assertFalse("Toggle starts off", viewModel.micController.micToggleOn.value)
        viewModel.micController.setMicToggled(true)
        val onState = withTimeoutOrNull(2000L) {
            viewModel.micState.first { it == MicState.ON }
        }
        assertEquals("Overlay mic toggle on opens micState to ON", MicState.ON, onState)

        viewModel.micController.setMicToggled(false)
        val offState = withTimeoutOrNull(2000L) {
            viewModel.micState.first { it == MicState.OFF }
        }
        assertEquals("Overlay mic toggle off returns micState to OFF", MicState.OFF, offState)

        // Overlay PTT path (OfflineAwareRoomHost.kt line 199)
        viewModel.micController.setPttPressed(true)
        val pttOn = withTimeoutOrNull(2000L) {
            viewModel.micState.first { it == MicState.ON }
        }
        assertEquals("Overlay PTT press opens micState to ON", MicState.ON, pttOn)

        viewModel.micController.setPttPressed(false)
        val pttOff = withTimeoutOrNull(2000L) {
            viewModel.micState.first { it == MicState.OFF }
        }
        assertEquals("Overlay PTT release returns micState to OFF", MicState.OFF, pttOff)

        transport.stop()
    }
}

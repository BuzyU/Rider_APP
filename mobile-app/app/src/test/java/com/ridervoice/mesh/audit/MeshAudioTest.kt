package com.ridervoice.mesh.audit

import android.media.AudioRecord
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MeshAudioTest {

    private class FakeVoiceTransport : VoiceTransport {
        val _state = MutableStateFlow(VoiceTransportState.CONNECTED)
        override val state: StateFlow<VoiceTransportState> = _state

        val _micState = MutableStateFlow(MicState.OFF)
        override val micState: StateFlow<MicState> = _micState

        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.OFFLINE_MESH)
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val participants: StateFlow<List<com.ridervoice.mesh.transport.UnifiedVoicePeer>> = MutableStateFlow(emptyList())

        var micEnabledRequested: Boolean = false

        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            micEnabledRequested = enabled
            _micState.value = if (enabled) MicState.ON else MicState.OFF
        }
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    private class FakeMeshLink : MeshLink {
        override val linkId = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    private class FakeAudioRecordSource : AudioRecordSource {
        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val audioSessionId: Int = 42
        override fun startRecording() {}
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
        override fun stop() {}
        override fun release() {}
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

    private fun createSineFrame(amplitude: Short = 15000, freqHz: Double = 1000.0): ByteArray {
        val bytes = ByteArray(640)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            val sample = (amplitude * Math.sin(2.0 * Math.PI * freqHz * i / 16000.0)).toInt().toShort()
            buf.putShort(sample)
        }
        return bytes
    }

    private fun createSilenceFrame(): ByteArray = ByteArray(640)

    @Test
    fun meshVoxBridgeSingleCaptureGatesFramesByVoxAndPtt() = runTest {
        val fakeTransport = FakeVoiceTransport()
        val bridge = MeshVoxBridge(transport = fakeTransport, dispatcher = Dispatchers.Unconfined)

        val silence = createSilenceFrame()
        val speech = createSineFrame()

        var now = 1_000_000L

        // Initial silence: gate must be closed
        assertFalse(bridge.shouldTransmitPcmFrame(silence, now))
        assertFalse(bridge.isGateOpen.value)

        // Feed speech: first 2 frames (40ms) do not exceed 60ms attack window
        now += 20L
        assertFalse(bridge.shouldTransmitPcmFrame(speech, now))
        now += 20L
        assertFalse(bridge.shouldTransmitPcmFrame(speech, now))

        // 3rd frame (60ms total attack): VOX speech gate opens
        now += 20L
        assertTrue(bridge.shouldTransmitPcmFrame(speech, now))
        assertTrue(bridge.isGateOpen.value)

        // Turn VOX off: gate closes
        bridge.setVoxEnabled(false)
        now += 20L
        assertFalse(bridge.shouldTransmitPcmFrame(speech, now))
        assertFalse(bridge.isGateOpen.value)

        // PTT override: transmits immediately even with silence and VOX disabled
        bridge.setPttPressed(true)
        assertTrue(bridge.shouldTransmitPcmFrame(silence, now))
        assertTrue(bridge.isGateOpen.value)

        bridge.setPttPressed(false)
        assertFalse(bridge.shouldTransmitPcmFrame(silence, now))

        bridge.close()
    }

    @Test
    fun revokedMicPermissionTransitionsToPermissionDeniedNotHardwareBusy() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val link = FakeMeshLink()

        var permissionGranted = false
        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            hasMicPermission = { permissionGranted },
            audioRecordFactory = { FakeAudioRecordSource() }
        )
        val session = OfflineSession.create(1_000L)

        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)

        // Permission is revoked: requesting mic must transition to PERMISSION_DENIED, NOT HARDWARE_BUSY
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.PERMISSION_DENIED, transport.micState.value)

        // Grant permission and retry: transitions to ON
        permissionGranted = true
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.ON, transport.micState.value)

        // Revoke permission and request mic again: transitions to PERMISSION_DENIED
        permissionGranted = false
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.PERMISSION_DENIED, transport.micState.value)

        transport.stop()
    }
}

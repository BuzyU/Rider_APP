package com.ridervoice.mesh.audit

import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TransportPauseResumeTest {

    private class FakeLiveKitPort : LiveKitPort {
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
        override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
        override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())

        val callLog = mutableListOf<String>()

        override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {
            callLog += "connect"
        }

        override fun disconnect() {
            callLog += "disconnect"
        }

        override fun setUserMuted(muted: Boolean) {
            callLog += "setUserMuted($muted)"
        }

        override fun setDeafened(deafened: Boolean) {
            callLog += "setDeafened($deafened)"
        }
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

    @Test
    fun t13_liveKitPauseAndResumeMutesAndRestoresWithoutDisconnect() = runTest {
        val port = FakeLiveKitPort()
        val arbiter = AudioOwnershipArbiter()
        val transport = LiveKitVoiceTransport(liveKitPort = port, audioOwnershipArbiter = arbiter)

        transport.start(VoiceSession.Online("wss://test.livekit.io", "room-1", { "token" }))
        assertEquals("LIVEKIT owner expected", AudioOwner.LIVEKIT, arbiter.owner.value)

        // User unmutes
        transport.setMicrophoneEnabled(true)
        assertEquals("setUserMuted(false)", port.callLog.last())

        // Pause by coordinator
        transport.pause()
        assertEquals("setUserMuted(true)", port.callLog.last())
        assertFalse("disconnect must never be called on pause", port.callLog.contains("disconnect"))

        // Resume: user mic was enabled, so it should be restored to unmuted
        transport.resume()
        assertEquals("setUserMuted(false)", port.callLog.last())
        assertFalse("disconnect must never be called on resume", port.callLog.contains("disconnect"))

        // User mutes himself, then coordinator pauses and resumes
        transport.setMicrophoneEnabled(false)
        assertEquals("setUserMuted(true)", port.callLog.last())

        transport.pause()
        assertEquals("setUserMuted(true)", port.callLog.last())

        transport.resume()
        assertEquals("User was muted before pause, so remains muted on resume", "setUserMuted(true)", port.callLog.last())
        assertFalse("disconnect was never called", port.callLog.contains("disconnect"))
    }

    @Test
    fun t14_meshPauseReleasesAudioOwnerAndResumeReclaims() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val fakeLink = FakeMeshLink()
        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { fakeLink }
        )
        val session = OfflineSession.create(1_000L)

        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        transport.pause()
        assertEquals("Audio owner must be released on pause", AudioOwner.NONE, arbiter.owner.value)
        assertFalse("Capture must be stopped on pause", transport.isCapturing())
        assertFalse("Playback must be stopped on pause", transport.isPlaying())

        transport.resume()
        assertEquals("Audio owner must be reclaimed on resume", AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        transport.stop()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }
}


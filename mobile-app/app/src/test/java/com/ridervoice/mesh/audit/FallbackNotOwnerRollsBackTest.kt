package com.ridervoice.mesh.audit

import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackNotOwnerRollsBackTest {

    private class TestLiveKit : VoiceTransport {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)

        var paused = false
        var resumed = false

        override suspend fun start(session: VoiceSession) {
            _state.value = VoiceTransportState.CONNECTED
        }
        override suspend fun stop() {
            _state.value = VoiceTransportState.STOPPED
        }
        override suspend fun pause() { paused = true; resumed = false }
        override suspend fun resume() { resumed = true; paused = false }
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    private class TestMesh : VoiceTransport, MeshControl {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)

        val _hasValidRoute = MutableStateFlow(true)
        override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

        override suspend fun startDiscoveryOnly(session: OfflineSession) {
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun enableAudio(): EnableAudioResult {
            return EnableAudioResult.Failure.OwnershipDenied
        }

        override suspend fun disableAudio() {}
        override suspend fun pauseCaptureOnly() {}
        override suspend fun resumeCaptureOnly() {}
        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() { _state.value = VoiceTransportState.STOPPED }
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    @Test
    fun fallbackNotOwner_rollsBackToLiveKit() = runTest {
        val liveKit = TestLiveKit()
        val mesh = TestMesh()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)

        val success = coordinator.fallbackToMesh()

        assertFalse("Fallback must return false when ownership is denied", success)
        assertTrue("LiveKit must be resumed on rollback", liveKit.resumed)
        assertEquals("Active kind must remain ONLINE_LIVEKIT", PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }
}


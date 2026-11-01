package com.ridervoice.mesh

import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 Integration Tests:
 * - AudioOwnershipArbiter strict mutual exclusion
 * - UnifiedVoicePeer projection and distinction
 * - VoiceSessionCoordinator atomic transport handoff
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Stage2IntegrationTest {

    @Test
    fun `audio ownership arbiter guarantees strict mutual exclusion`() {
        val arbiter = AudioOwnershipArbiter()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)

        // LiveKit claims audio ownership
        var offlineStopped = false
        assertTrue(arbiter.claimLiveKit(onStopOfflineFirst = { offlineStopped = true }))
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)
        assertFalse(offlineStopped)

        // Offline mesh cannot claim while LiveKit owns it
        assertFalse(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Controlled handoff from LiveKit to Offline Mesh
        var liveKitSuspended = false
        assertTrue(arbiter.handoffLiveKitToOfflineMesh(onSuspendLiveKitCapture = { liveKitSuspended = true }))
        assertTrue(liveKitSuspended)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // LiveKit cannot reclaim without stopping offline first
        assertTrue(arbiter.claimLiveKit(onStopOfflineFirst = { offlineStopped = true }))
        assertTrue(offlineStopped)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Controlled handoff back to LiveKit
        var offlineStopped2 = false
        var liveKitResumed = false
        assertTrue(
            arbiter.handoffOfflineMeshToLiveKit(
                onStopOfflineCapture = { offlineStopped2 = true },
                onResumeLiveKitCapture = { liveKitResumed = true }
            )
        )
        assertTrue(liveKitResumed)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        arbiter.release(AudioOwner.LIVEKIT)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }

    @Test
    fun `voice session coordinator manages atomic handoff between transports`() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val mockLiveKit = FakeVoiceTransport()
        val mockMesh = FakeVoiceTransport()

        val coordinator = VoiceSessionCoordinator(mockLiveKit, mockMesh, arbiter)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)

        val sessionOnline = VoiceSession.Online("wss://livekit.example.com", "room-1", { "token" })
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, sessionOnline)
        assertTrue(mockLiveKit.isStarted)
        assertFalse(mockMesh.isStarted)

        val sessionOffline = VoiceSession.Offline(OfflineSession.create(1000L))
        coordinator.switchToTransport(PeerTransportKind.OFFLINE_MESH, sessionOffline)

        // Previous transport was stopped, next was started
        assertFalse(mockLiveKit.isStarted)
        assertTrue(mockMesh.isStarted)
        assertEquals(PeerTransportKind.OFFLINE_MESH, coordinator.activeKind.value)

        coordinator.stopCurrent()
        assertFalse(mockMesh.isStarted)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }

    private class FakeVoiceTransport : VoiceTransport {
        var isStarted = false
        private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)

        override suspend fun start(session: VoiceSession) {
            isStarted = true
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun stop() {
            isStarted = false
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun pause() {}
        override suspend fun resume() {}

        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }
}


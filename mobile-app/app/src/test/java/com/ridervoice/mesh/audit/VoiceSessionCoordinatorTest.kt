package com.ridervoice.mesh.audit

import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11 & T22 regression test suite for D3: Non-disconnecting handoff, discovery-only phase, and coordinator rollback.
 */
class VoiceSessionCoordinatorTest {

    private class FakeLiveKitTransport : VoiceTransport {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)

        var startedSessions = mutableListOf<VoiceSession>()
        var stoppedCount = 0
        var failOnStart = false

        override suspend fun start(session: VoiceSession) {
            startedSessions.add(session)
            if (failOnStart) {
                _state.value = VoiceTransportState.FAILED
                throw RuntimeException("Simulated LiveKit start failure")
            }
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun stop() {
            stoppedCount++
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    private class FakeMeshTransport : VoiceTransport, MeshControl {
        val _state = MutableStateFlow(VoiceTransportState.STOPPED)
        override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)

        val _hasValidRoute = MutableStateFlow(false)
        override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

        var discoverySessions = mutableListOf<OfflineSession>()
        var audioEnabled = false
        var failOnEnableAudio = false
        var stopCount = 0

        override suspend fun startDiscoveryOnly(session: OfflineSession) {
            discoverySessions.add(session)
            _state.value = VoiceTransportState.CONNECTED
        }

        override suspend fun enableAudio() {
            if (failOnEnableAudio) {
                throw RuntimeException("Simulated mesh enableAudio hardware failure")
            }
            audioEnabled = true
        }

        override suspend fun disableAudio() {
            audioEnabled = false
        }

        override suspend fun start(session: VoiceSession) {
            if (session is VoiceSession.Offline) {
                startDiscoveryOnly(session.session)
                enableAudio()
            }
        }

        override suspend fun stop() {
            stopCount++
            disableAudio()
            _state.value = VoiceTransportState.STOPPED
        }

        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    @Test
    fun fallbackToMeshRequiresValidRoute() = runTest {
        val liveKit = FakeLiveKitTransport()
        val mesh = FakeMeshTransport()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)
        assertEquals(VoiceTransportState.CONNECTED, liveKit.state.value)

        // Mesh has no valid route yet -> fallback must refuse
        mesh._hasValidRoute.value = false
        val fallbackResult = coordinator.fallbackToMesh()
        assertFalse("Fallback must return false when no valid mesh route exists", fallbackResult)
        assertEquals("LiveKit must not be stopped when fallback refuses", 0, liveKit.stoppedCount)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }

    @Test
    fun fallbackToMeshRollsBackToLiveKitWhenMeshAudioFails() = runTest {
        // T11: Coordinator rolls back to LiveKit if mesh enableAudio() throws
        val liveKit = FakeLiveKitTransport()
        val mesh = FakeMeshTransport()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)

        mesh._hasValidRoute.value = true
        mesh.failOnEnableAudio = true // Inject failure on mesh enableAudio

        val fallbackResult = coordinator.fallbackToMesh()
        assertFalse("Fallback must return false when enableAudio fails", fallbackResult)

        // Verifications:
        // 1. Mesh audio was not left enabled
        assertFalse(mesh.audioEnabled)
        // 2. Arbiter does not retain OFFLINE_MESH ownership
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
        // 3. LiveKit was restarted as part of rollback
        assertEquals(2, liveKit.startedSessions.size)
        assertEquals(onlineSession, liveKit.startedSessions.last())
        assertEquals(VoiceTransportState.CONNECTED, liveKit.state.value)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }

    @Test
    fun fallbackToMeshSucceedsWhenRouteExistsAndAudioEnables() = runTest {
        val liveKit = FakeLiveKitTransport()
        val mesh = FakeMeshTransport()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)

        mesh._hasValidRoute.value = true
        val fallbackResult = coordinator.fallbackToMesh()
        assertTrue(fallbackResult)

        assertEquals(1, liveKit.stoppedCount)
        assertTrue(mesh.audioEnabled)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals(PeerTransportKind.OFFLINE_MESH, coordinator.activeKind.value)
    }

    @Test
    fun recoveryToLiveKitSucceedsAndDisablesMeshAudio() = runTest {
        val liveKit = FakeLiveKitTransport()
        val mesh = FakeMeshTransport()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)

        mesh._hasValidRoute.value = true
        assertTrue(coordinator.fallbackToMesh())
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // Recover back to LiveKit
        val recoverResult = coordinator.recoverToLiveKit()
        assertTrue(recoverResult)

        assertFalse("Mesh audio must be disabled after recovery", mesh.audioEnabled)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
        assertEquals(VoiceTransportState.CONNECTED, liveKit.state.value)
    }

    @Test
    fun recoveryToLiveKitRollsBackToMeshWhenLiveKitFailsToConnect() = runTest {
        val liveKit = FakeLiveKitTransport()
        val mesh = FakeMeshTransport()
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)

        val onlineSession = VoiceSession.Online("wss://livekit.example.com", "room-123", { "token" }, "room-123")
        coordinator.switchToTransport(PeerTransportKind.ONLINE_LIVEKIT, onlineSession)

        mesh._hasValidRoute.value = true
        assertTrue(coordinator.fallbackToMesh())
        assertTrue(mesh.audioEnabled)

        // Inject LiveKit failure on reconnect
        liveKit.failOnStart = true
        val recoverResult = coordinator.recoverToLiveKit()
        assertFalse("Recovery must return false when LiveKit fails to connect", recoverResult)

        // Verifications:
        // 1. Rolled back to mesh
        assertTrue("Mesh audio must be re-enabled on recovery failure", mesh.audioEnabled)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals(PeerTransportKind.OFFLINE_MESH, coordinator.activeKind.value)
    }
}


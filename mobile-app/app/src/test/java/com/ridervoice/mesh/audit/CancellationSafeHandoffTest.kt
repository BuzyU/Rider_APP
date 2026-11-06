package com.ridervoice.mesh.audit

import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationSafeHandoffTest {

    @Test(timeout = 5000)
    fun fallbackToMesh_cancelledMidHandoff_rollsBackToLiveKitCleanly() = runTest {
        val arbiter = AudioOwnershipArbiter()
        arbiter.claimLiveKit()

        val gate = CompletableDeferred<Unit>()
        var liveKitResumed = false

        val fakeLk = object : VoiceTransport {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.ON)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() { liveKitResumed = true }
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
        }

        val fakeMesh = object : VoiceTransport, MeshControl {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.OFF)
            override val hasValidRoute = MutableStateFlow(true)
            override val micController: MicController? = null
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
            override suspend fun pauseCaptureOnly() {}
            override suspend fun resumeCaptureOnly() {}
            override suspend fun enableAudio(): EnableAudioResult {
                gate.await() // Suspend indefinitely until cancelled
                return EnableAudioResult.Success
            }
            override suspend fun disableAudio() {}
            override suspend fun startDiscoveryOnly(session: OfflineSession) {}
            override fun setHelloIntervalMs(intervalMs: Long) {}
        }

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = fakeLk,
            meshTransport = fakeMesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = HolderOfflineSecretProvider(OfflineSessionHolder())
        )

        val job = launch {
            coordinator.fallbackToMesh()
        }
        // Advance past quiet check into enableAudio gate
        advanceTimeBy(500L)

        // Cancel job while suspended in enableAudio()
        job.cancel()
        advanceTimeBy(100L)

        // Final state must roll back to LiveKit owner and resumed
        assertEquals("Arbiter owner must roll back to LIVEKIT on cancelled fallback", AudioOwner.LIVEKIT, arbiter.owner.value)
        assertTrue("LiveKit must be resumed on cancelled fallback", liveKitResumed)
        assertEquals("Active kind must roll back to ONLINE_LIVEKIT", PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }

    @Test(timeout = 5000)
    fun recoverToLiveKit_cancelledMidHandoff_rollsBackToMeshCleanly() = runTest {
        val arbiter = AudioOwnershipArbiter()
        arbiter.tryClaimOfflineMesh()

        val gate = CompletableDeferred<Unit>()
        var meshCaptureResumed = false

        val fakeLk = object : VoiceTransport {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.OFF)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {
                gate.await() // Suspend indefinitely during resume until cancelled
            }
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
        }

        val fakeMesh = object : VoiceTransport, MeshControl {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.ON)
            override val hasValidRoute = MutableStateFlow(true)
            override val micController: MicController? = null
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
            override suspend fun pauseCaptureOnly() {}
            override suspend fun resumeCaptureOnly() { meshCaptureResumed = true }
            override suspend fun enableAudio(): EnableAudioResult = EnableAudioResult.Success
            override suspend fun disableAudio() {}
            override suspend fun startDiscoveryOnly(session: OfflineSession) {}
            override fun setHelloIntervalMs(intervalMs: Long) {}
        }

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = fakeLk,
            meshTransport = fakeMesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = HolderOfflineSecretProvider(OfflineSessionHolder())
        )

        val job = launch {
            coordinator.recoverToLiveKit()
        }
        advanceTimeBy(100L)

        // Cancel job while suspended in resume()
        job.cancel()
        advanceTimeBy(100L)

        // Final state must roll back to Mesh owner and capturing
        assertEquals("Arbiter owner must roll back to OFFLINE_MESH on cancelled recovery", AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertTrue("Mesh capture must be resumed on cancelled recovery", meshCaptureResumed)
        assertEquals("Active kind must roll back to OFFLINE_MESH", PeerTransportKind.OFFLINE_MESH, coordinator.activeKind.value)
    }
}

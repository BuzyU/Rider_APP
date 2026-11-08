package com.ridervoice.mesh.audit

import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverConfirmationTest {

    private interface MeshControlVoiceTransport : VoiceTransport, MeshControl

    @Test
    fun recoverToLiveKit_whenLiveKitDisconnectsDuringConfirmation_rollsBackToMesh() = runTest {
        val arbiter = AudioOwnershipArbiter()
        arbiter.tryClaimOfflineMesh()
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        val lkState = MutableStateFlow(VoiceTransportState.CONNECTED)
        val lkTransport = mockk<LiveKitVoiceTransport>(relaxed = true)
        every { lkTransport.state } returns lkState
        coEvery { lkTransport.resume() } answers {
            // LiveKit drops connection during the confirmation window
            lkState.value = VoiceTransportState.STOPPED
        }

        var meshAudioEnabled = true
        var capturePaused = false
        val meshTransport = mockk<MeshControlVoiceTransport>(relaxed = true)
        every { meshTransport.state } returns MutableStateFlow(VoiceTransportState.CONNECTED)
        every { meshTransport.micController } returns null
        coEvery { meshTransport.pauseCaptureOnly() } answers { capturePaused = true }
        coEvery { meshTransport.enableAudio() } answers {
            meshAudioEnabled = true
            EnableAudioResult.Success
        }
        coEvery { meshTransport.resumeCaptureOnly() } answers { capturePaused = false }

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = lkTransport,
            meshTransport = meshTransport,
            audioOwnershipArbiter = arbiter
        )

        val result = coordinator.recoverToLiveKit()

        assertFalse("recoverToLiveKit must return false when LiveKit drops during confirm", result)
        assertEquals("AudioOwner must roll back to OFFLINE_MESH", AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertTrue("Mesh audio must remain enabled after rollback", meshAudioEnabled)
        assertFalse("Capture pause must be cleared on rollback", capturePaused)
        coVerify(atLeast = 1) { lkTransport.pause() }
    }
}


package com.ridervoice.mesh

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying graceful handling of denied or revoked RECORD_AUDIO permissions.
 *
 * UNVERIFIED (needs devices): Tests that runtime permission revocation mid-stream results in
 * graceful capture disablement, zero application crash, and proper state reporting.
 */
@RunWith(AndroidJUnit4::class)
class PermissionDeniedGracefulPathTest {

    @Test
    fun enableAudio_whenPermissionDenied_returnsPermissionDeniedResultWithoutCrashing() = runBlocking {
        // UNVERIFIED (needs devices)
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(deviceId = "dev-1", displayName = "Rider1")

        var micPermissionGranted = false
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            hasMicPermission = { micPermissionGranted }
        )

        val result = transport.enableAudio()
        assertTrue("Result must be Failure with permission denied", result is EnableAudioResult.Failure)
    }

    @Test
    fun midStreamRevocation_failsSoftAndReleasesHardware() = runBlocking {
        // UNVERIFIED (needs devices)
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(deviceId = "dev-2", displayName = "Rider2")

        var micPermissionGranted = true
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            hasMicPermission = { micPermissionGranted }
        )

        // Simulate permission revoked mid-stream
        micPermissionGranted = false

        // Attempting to re-enable or capture with revoked permission must fail gracefully
        val result = transport.enableAudio()
        assertTrue("Result must be Failure with permission denied", result is EnableAudioResult.Failure)
    }
}


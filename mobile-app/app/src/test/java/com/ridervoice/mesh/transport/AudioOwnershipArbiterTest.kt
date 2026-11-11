package com.ridervoice.mesh.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOwnershipArbiterTest {

    @Test
    fun mutualExclusion_tryClaimOfflineMeshFailsWhenLiveKitOwns() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)

        // LiveKit claims ownership
        val lkClaim = arbiter.claimLiveKit()
        assertTrue("LiveKit claim must succeed", lkClaim)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Offline mesh tryClaim must be rejected
        val meshClaim = arbiter.tryClaimOfflineMesh()
        assertFalse("Mesh claim must fail when LiveKit holds ownership", meshClaim)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Release LiveKit
        arbiter.release(AudioOwner.LIVEKIT)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)

        // Now offline mesh can claim
        val meshClaim2 = arbiter.tryClaimOfflineMesh()
        assertTrue("Mesh claim must succeed when ownership is NONE", meshClaim2)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
    }

    @Test
    fun concurrentClaims_maintainStrictMutualExclusion() = runBlocking {
        val arbiter = AudioOwnershipArbiter()

        // Launch concurrent claims across multiple coroutines
        val jobs = (1..20).map { i ->
            async {
                if (i % 2 == 0) {
                    arbiter.claimLiveKit()
                } else {
                    arbiter.tryClaimOfflineMesh()
                }
            }
        }
        jobs.awaitAll()

        // The owner must be either LIVEKIT or OFFLINE_MESH, never invalid or corrupt
        val finalOwner = arbiter.owner.value
        assertTrue("Owner must be valid single owner", finalOwner == AudioOwner.LIVEKIT || finalOwner == AudioOwner.OFFLINE_MESH)

        arbiter.releaseAll()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }
}


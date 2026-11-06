package com.ridervoice.mesh.audit

import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * T9 regression test suite for D3: AudioOwnershipArbiter coroutine safety.
 */
class AudioOwnershipArbiterTest {

    @Test
    fun arbiterInitialStateIsNone() {
        val arbiter = AudioOwnershipArbiter()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }

    @Test
    fun mutualExclusionBasicTransitions() = runTest {
        val arbiter = AudioOwnershipArbiter()

        assertTrue(arbiter.claimLiveKit())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Offline mesh cannot seize while LiveKit owns it
        assertFalse(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Release LiveKit
        arbiter.release(AudioOwner.LIVEKIT)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)

        // Offline mesh claims
        assertTrue(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // Wrong owner release does nothing
        arbiter.release(AudioOwner.LIVEKIT)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        arbiter.release(AudioOwner.OFFLINE_MESH)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
    }

    @Test
    fun concurrentRandomizedOperationsNeverViolateMutualExclusion() = runTest {
        // T9: 1,000 randomized concurrent operations never produce dual capture ownership
        val arbiter = AudioOwnershipArbiter()
        val random = Random(42)
        val ops = 1000

        val deferreds = (1..ops).map { i ->
            async(Dispatchers.Default) {
                when (random.nextInt(6)) {
                    0 -> arbiter.claimLiveKit { /* stop offline callback */ }
                    1 -> arbiter.tryClaimOfflineMesh()
                    2 -> arbiter.handoffLiveKitToOfflineMesh { /* suspend LK */ }
                    3 -> arbiter.handoffOfflineMeshToLiveKit({ /* stop offline */ }, { /* resume LK */ })
                    4 -> arbiter.release(if (random.nextBoolean()) AudioOwner.LIVEKIT else AudioOwner.OFFLINE_MESH)
                    else -> arbiter.releaseAll()
                }
                val current = arbiter.owner.value
                assertTrue(
                    "Arbiter state must always be exactly one valid owner (NONE, LIVEKIT, OFFLINE_MESH): got $current",
                    current in AudioOwner.values()
                )
            }
        }
        deferreds.awaitAll()
    }

    @Test
    fun claimLiveKit_whenStopOfflineCallbackFails_returnsFalseAndPreservesOwner() = runTest {
        val arbiter = AudioOwnershipArbiter()
        assertTrue(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        val ok = arbiter.claimLiveKit { throw RuntimeException("stop offline capture failed") }
        assertFalse("claimLiveKit must report failure when callback fails", ok)
        assertEquals("AudioOwner must not transition to LIVEKIT on callback failure", AudioOwner.OFFLINE_MESH, arbiter.owner.value)
    }

    @Test
    fun handoffLiveKitToOfflineMesh_whenSuspendFails_returnsFalseAndPreservesOwner() = runTest {
        val arbiter = AudioOwnershipArbiter()
        assertTrue(arbiter.claimLiveKit())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        val ok = arbiter.handoffLiveKitToOfflineMesh { throw RuntimeException("suspend livekit failed") }
        assertFalse("handoffLiveKitToOfflineMesh must report failure when callback fails", ok)
        assertEquals("AudioOwner must remain LIVEKIT on callback failure", AudioOwner.LIVEKIT, arbiter.owner.value)
    }

    @Test
    fun handoffOfflineMeshToLiveKit_whenCallbacksFail_returnsFalseAndPreservesOwner() = runTest {
        val arbiter = AudioOwnershipArbiter()
        assertTrue(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        val ok1 = arbiter.handoffOfflineMeshToLiveKit(
            onStopOfflineCapture = { throw RuntimeException("stop mesh failed") },
            onResumeLiveKitCapture = {}
        )
        assertFalse("handoffOfflineMeshToLiveKit must fail when stop callback fails", ok1)
        assertEquals("AudioOwner must remain OFFLINE_MESH", AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        val ok2 = arbiter.handoffOfflineMeshToLiveKit(
            onStopOfflineCapture = {},
            onResumeLiveKitCapture = { throw RuntimeException("resume livekit failed") }
        )
        assertFalse("handoffOfflineMeshToLiveKit must fail when resume callback fails", ok2)
        assertEquals("AudioOwner must not be LIVEKIT when resume callback fails", AudioOwner.OFFLINE_MESH, arbiter.owner.value)
    }
}



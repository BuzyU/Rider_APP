package com.ridervoice.mesh.audit

import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationWithContendedMutexTest {

    private class CountingNetworkLocks : NetworkLocks {
        val acquireCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        override fun acquire() { acquireCount.incrementAndGet() }
        override fun release() { releaseCount.incrementAndGet() }
    }

    private class ControllableMeshLink(
        private val onStartStarted: CompletableDeferred<Unit>,
        private val proceedWithStart: CompletableDeferred<Unit>
    ) : MeshLink {
        override val linkId = "controllable-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {
            onStartStarted.complete(Unit)
            proceedWithStart.await()
        }
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    @Test
    fun cancellationWithContendedMutex_teardownFinishesInNonCancellable() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val arbiter = AudioOwnershipArbiter()
        val locks = CountingNetworkLocks()
        val session = OfflineSession.create(1000L)

        val linkStarted = CompletableDeferred<Unit>()
        val releaseLink = CompletableDeferred<Unit>()

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { com.ridervoice.mesh.identity.MeshIdentity(UUID.randomUUID().toString(), "Tester") },
            audioOwnershipArbiter = arbiter,
            clock = { 1000L },
            dispatcher = testDispatcher,
            gateAllowed = { true },
            locks = locks,
            linkFactory = { ControllableMeshLink(linkStarted, releaseLink) },
            broadcastProvider = { emptyList() }
        )

        val startJob = launch {
            transport.start(VoiceSession.Offline(session))
        }

        // Wait until transport reaches link.start() inside startMutex
        linkStarted.await()

        // Cancel the start job while link.start() is suspended inside startMutex
        startJob.cancel()
        // Release the suspension in link.start() so cancellation can proceed
        releaseLink.complete(Unit)
        startJob.join()

        // Verify state is clean and teardown completed via NonCancellable
        assertEquals("Transport state must end in STOPPED after cancellation teardown", VoiceTransportState.STOPPED, transport.state.value)
        assertEquals("Audio ownership arbiter owner must be NONE", AudioOwner.NONE, arbiter.owner.value)
        assertEquals("Network locks must be balanced", locks.acquireCount.get(), locks.releaseCount.get())
    }
}

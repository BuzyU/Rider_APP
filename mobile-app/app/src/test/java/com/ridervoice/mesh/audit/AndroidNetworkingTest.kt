package com.ridervoice.mesh.audit

import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidNetworkingTest {

    private class CountingNetworkLocks : NetworkLocks {
        var acquireCount = 0
        var releaseCount = 0
        override fun acquire() { acquireCount++ }
        override fun release() { releaseCount++ }
    }

    private class FakeTestLink(
        override val linkId: String = "fake",
        private val initialState: MeshLinkState = MeshLinkState.RUNNING
    ) : MeshLink {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<MeshLinkState> = _state
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
        val sent = CopyOnWriteArrayList<Pair<MeshEndpoint, ByteArray>>()

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload)
            return true
        }

        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() { _state.value = MeshLinkState.STOPPED }
    }

    @Test
    fun unauthenticatedDatagramMustNotBeLearnedAsEndpoint() = runBlocking {
        val port = 45469
        val link = UdpMeshLink(bindPort = port, gateAllowed = { true })
        try {
            link.start()

            // Send raw datagram from an unknown sender
            val senderSocket = DatagramSocket()
            try {
                val receivedDeferred = async {
                    withTimeoutOrNull(2_000L) {
                        link.incomingFrames.first()
                    }
                }
                delay(100L) // Ensure subscriber is attached

                val payload = "unauthenticated probe".toByteArray()
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port)
                senderSocket.send(packet)

                val received = receivedDeferred.await()
                assertTrue("Expected frame to be received by link", received != null)

                // D22 invariant: the sender endpoint must NOT be added to endpoints()
                assertTrue(
                    "Endpoints learned unauthenticated sender: ${link.endpoints()}",
                    link.endpoints().isEmpty()
                )

                // Explicit registration works as intended
                val trustedGateway = MeshEndpoint("192.168.1.1", port)
                link.registerEndpoint(trustedGateway)
                assertEquals(setOf(trustedGateway), link.endpoints())
            } finally {
                senderSocket.close()
            }
        } finally {
            link.stop()
        }
    }

    @Test
    fun networkLocksAcquiredOnStartReleasedOnStopAndSymmetric() = runBlocking {
        val locks = CountingNetworkLocks()
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { MeshIdentity(java.util.UUID.randomUUID().toString(), "Alice") },
            audioOwnershipArbiter = AudioOwnershipArbiter(),
            gateAllowed = { true },
            linkFactory = { FakeTestLink(initialState = MeshLinkState.RUNNING) },
            locks = locks
        )

        // 1. Successful start acquires lock once
        transport.start(VoiceSession.Offline(OfflineSession.create(1000L)))
        assertEquals("Lock should be acquired once on start", 1, locks.acquireCount)
        assertEquals("Lock should not be released while running", 0, locks.releaseCount)

        // 2. Stop releases lock once
        transport.stop()
        assertEquals("Acquire count remains 1", 1, locks.acquireCount)
        assertEquals("Lock should be released once on stop", 1, locks.releaseCount)

        // 3. Double stop releases only once
        transport.stop()
        assertEquals("Calling stop twice should not release again", 1, locks.releaseCount)

        // 4. Failed start leaves zero outstanding acquires
        val failLocks = CountingNetworkLocks()
        val failingTransport = OfflineMeshVoiceTransport(
            identityProvider = { MeshIdentity(java.util.UUID.randomUUID().toString(), "Bob") },
            audioOwnershipArbiter = AudioOwnershipArbiter(),
            gateAllowed = { true },
            linkFactory = { FakeTestLink(initialState = MeshLinkState.FAILED) },
            locks = failLocks
        )
        failingTransport.start(VoiceSession.Offline(OfflineSession.create(1000L)))
        assertEquals("Failed start must leave zero outstanding acquires", 0, failLocks.acquireCount - failLocks.releaseCount)
    }

    @Test
    fun broadcastProviderRecomputedOnEveryHeartbeat() = runBlocking {
        val beatCount = AtomicInteger(0)
        val ep1 = MeshEndpoint("192.168.1.255", 45454)
        val ep2 = MeshEndpoint("192.168.2.255", 45454)
        val provider = {
            if (beatCount.getAndIncrement() == 0) listOf(ep1) else listOf(ep2)
        }
        val fakeLink = FakeTestLink()
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { MeshIdentity(java.util.UUID.randomUUID().toString(), "Charlie") },
            audioOwnershipArbiter = AudioOwnershipArbiter(),
            gateAllowed = { true },
            clock = { 1_000L },
            linkFactory = { fakeLink },
            broadcastProvider = provider
        )

        transport.start(VoiceSession.Offline(OfflineSession.create(1000L)))

        // Wait until at least 2 heartbeats have fired
        withTimeout(5_000L) {
            while (fakeLink.sent.none { it.first == ep2 }) {
                delay(100L)
            }
        }

        assertTrue("First heartbeat should target ep1", fakeLink.sent.any { it.first == ep1 })
        assertTrue("Second heartbeat should target recomputed ep2", fakeLink.sent.any { it.first == ep2 })
        transport.stop()
    }

    @Test
    fun networkBinderInvokedOnSocketStart() = runBlocking {
        val binderInvoked = AtomicBoolean(false)
        val link = UdpMeshLink(
            bindPort = 45479,
            gateAllowed = { true },
            networkBinder = { binderInvoked.set(true) }
        )
        try {
            link.start()
            assertTrue("networkBinder must be called during socket start", binderInvoked.get())
        } finally {
            link.stop()
        }
    }
}

package com.ridervoice.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridervoice.mesh.link.AndroidNetworkLocks
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.UdpMeshLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback socket and network link verification on real Android runtime.
 *
 * UNVERIFIED (needs devices): Validates actual OS datagram socket creation on 127.0.0.1,
 * bidirectional send/receive, socket network binding (Android 12+), and MulticastLock lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class UdpMeshLinkLoopbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loopbackTwoSocket_bidirectionalSendReceive_andNetworkBinder() = runBlocking {
        // UNVERIFIED (needs devices)
        val port1 = 45461
        val port2 = 45462

        val binder1Called = AtomicBoolean(false)
        val binder2Called = AtomicBoolean(false)

        val link1 = UdpMeshLink(
            linkId = "link1",
            bindPort = port1,
            gateAllowed = { true },
            networkBinder = { socket ->
                assertNotNull(socket)
                binder1Called.set(true)
            }
        )

        val link2 = UdpMeshLink(
            linkId = "link2",
            bindPort = port2,
            gateAllowed = { true },
            networkBinder = { socket ->
                assertNotNull(socket)
                binder2Called.set(true)
            }
        )

        try {
            link1.start()
            link2.start()

            assertEquals(MeshLinkState.RUNNING, link1.state.value)
            assertEquals(MeshLinkState.RUNNING, link2.state.value)
            assertTrue("Link 1 network binder must be invoked on socket creation", binder1Called.get())
            assertTrue("Link 2 network binder must be invoked on socket creation", binder2Called.get())

            val payload = "HELLO_FROM_SOCKET_1".toByteArray(Charsets.UTF_8)
            val targetEndpoint = MeshEndpoint("127.0.0.1", port2)

            val sent = link1.send(targetEndpoint, payload)
            assertTrue("OS socket send must return true", sent)

            val receivedFrame = withTimeoutOrNull(3_000L) {
                link2.incomingFrames.first()
            }

            assertNotNull("Link 2 must receive datagram frame over loopback interface", receivedFrame)
            assertArrayEquals(payload, receivedFrame?.data)
        } finally {
            link1.stop()
            link2.stop()
            assertEquals(MeshLinkState.STOPPED, link1.state.value)
            assertEquals(MeshLinkState.STOPPED, link2.state.value)
        }
    }

    @Test
    fun multicastLockLifecycle_acquireAndRelease() {
        // UNVERIFIED (needs devices)
        val locks = AndroidNetworkLocks(context)
        // Verify acquireMulticast and releaseMulticast execute without throwing
        locks.acquireMulticast()
        locks.releaseMulticast()

        // Verify active Wi-Fi locks lifecycle
        locks.acquire()
        locks.release()
    }
}


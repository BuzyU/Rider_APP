package com.ridervoice.mesh.link

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

class UdpBufferSizeTest {

    @Test
    fun udpSocket_configuresReceiveAndSendBufferSizes() = runBlocking {
        var capturedSocket: DatagramSocket? = null
        val link = UdpMeshLink(
            bindPort = 0,
            gateAllowed = { true },
            networkBinder = { capturedSocket = it }
        )
        link.start()
        try {
            val sock = capturedSocket
            assertTrue("Socket must be passed to networkBinder", sock != null)
            // Requested 256KB rx and 128KB tx. OS buffer sizes must be elevated (>= 64KB).
            assertTrue("Receive buffer size (${sock!!.receiveBufferSize}) must be >= 65536", sock.receiveBufferSize >= 65536)
            assertTrue("Send buffer size (${sock.sendBufferSize}) must be >= 65536", sock.sendBufferSize >= 65536)
        } finally {
            link.stop()
        }
    }
}


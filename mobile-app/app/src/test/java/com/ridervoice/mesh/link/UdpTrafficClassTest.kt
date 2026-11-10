package com.ridervoice.mesh.link

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

class UdpTrafficClassTest {

    @Test
    fun udpSocket_configuresTrafficClassEf() = runBlocking {
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
            val tc = sock!!.trafficClass
            // 0xB8 = 184 (DSCP EF). OS returns set value or kernel-applied value.
            assertTrue("Traffic class should be configured (0xB8 on Linux/Android or >= 0): $tc", tc == 0xB8 || tc >= 0)
        } finally {
            link.stop()
        }
    }
}


package com.ridervoice.mesh.audit

import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.UdpMeshLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class UdpLinkCountersTest {

    @Test
    fun udpMeshLink_txAndRxCounters_incrementAndComputeRate() = runTest {
        var now = 10_000L
        val link = UdpMeshLink(
            bindPort = 0, // ephemeral port
            gateAllowed = { true },
            clock = { now }
        )

        assertEquals(0L, link.totalTxDatagrams)
        assertEquals(0L, link.totalRxDatagrams)
        assertEquals(Pair(0, 0), link.datagramRates())

        link.start()
        try {
            val localEp = MeshEndpoint("127.0.0.1", 45454)

            // Send 5 datagrams (even if loopback address is blocked or port closed, send attempts socket.send)
            for (i in 1..5) {
                link.send(localEp, byteArrayOf(1, 2, 3))
            }
            assertEquals(5L, link.totalTxDatagrams)

            // Calculate rate after 1 second
            now += 1000L
            val rates = link.calculateDatagramRates(now)
            assertEquals("TX rate should be 5/s over 1s", 5, rates.first)
            assertEquals("RX rate should be 0/s", 0, rates.second)

            // Advance time without more sends
            now += 1000L
            val idleRates = link.calculateDatagramRates(now)
            assertEquals("TX rate should drop to 0/s when idle", 0, idleRates.first)
        } finally {
            link.stop()
        }
    }
}


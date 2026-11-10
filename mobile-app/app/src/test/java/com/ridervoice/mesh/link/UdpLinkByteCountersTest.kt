package com.ridervoice.mesh.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UdpLinkByteCountersTest {

    @Test
    fun udpMeshLink_txAndRxByteCounters_incrementAndComputeRate() = runTest {
        var now = 10_000L
        val link = UdpMeshLink(
            bindPort = 0,
            gateAllowed = { true },
            clock = { now }
        )

        assertEquals(0L, link.totalTxBytes)
        assertEquals(0L, link.totalRxBytes)
        assertEquals(Pair(0, 0), link.byteRates())

        link.start()
        try {
            val localEp = MeshEndpoint("127.0.0.1", 45454)
            val payload = ByteArray(100) { 0x42 }

            for (i in 1..5) {
                link.send(localEp, payload)
            }
            assertEquals(500L, link.totalTxBytes)

            now += 1000L
            val rates = link.calculateByteRates(now)
            assertEquals("TX byte rate should be 500 B/s over 1s", 500, rates.first)
            assertEquals("RX byte rate should be 0 B/s", 0, rates.second)

            now += 1000L
            val idleRates = link.calculateByteRates(now)
            assertEquals("TX byte rate should drop to 0 B/s when idle", 0, idleRates.first)
        } finally {
            link.stop()
        }
    }
}


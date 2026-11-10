package com.ridervoice.mesh.graph

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.link.MeshEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WallClockStepDoesNotExpireNeighboursTest {

    @Test
    fun wallClockJumpsForwardAndBackward_monotonicClockSteady_neighborsStay() {
        var wallClock = 1_000_000L
        var monotonicClock = 10_000L

        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(
            selfId = selfId,
            selfName = "Self",
            clock = { wallClock },
            monotonicClock = { monotonicClock }
        )

        val ep = MeshEndpoint("192.168.1.55", MeshConfig.MESH_PORT)
        repo.onHello(peerId, "Peer", hopCount = 0, endpoint = ep, neighborIds = emptyList())

        assertTrue("Neighbor must be present initially", repo.directNeighbors().any { it.deviceId == peerId })

        // Wall clock jumps +1 hour (e.g. NTP or timezone resync)
        wallClock += 3_600_000L
        // Monotonic clock advances by only 100 ms
        monotonicClock += 100L

        val expiredPlus1h = repo.expire()
        assertFalse("Neighbor must NOT be expired by wall clock +1h jump", expiredPlus1h)
        assertTrue("Neighbor must stay in repository", repo.directNeighbors().any { it.deviceId == peerId })
        assertTrue("Neighbor must stay in forwardingView", repo.forwardingView().neighbors.any { it.deviceId == peerId })

        // Wall clock jumps backward -1 hour
        wallClock -= 3_600_000L
        monotonicClock += 100L

        val expiredMinus1h = repo.expire()
        assertFalse("Neighbor must NOT be expired by wall clock -1h jump", expiredMinus1h)
        assertTrue("Neighbor must stay in repository after backward jump", repo.directNeighbors().any { it.deviceId == peerId })
        assertTrue("Neighbor must stay in forwardingView after backward jump", repo.forwardingView().neighbors.any { it.deviceId == peerId })
    }
}


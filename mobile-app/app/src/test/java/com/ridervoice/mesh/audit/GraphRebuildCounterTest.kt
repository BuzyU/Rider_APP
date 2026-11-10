package com.ridervoice.mesh.audit

import com.ridervoice.mesh.graph.MeshGraphRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class GraphRebuildCounterTest {

    @Test
    fun graphRebuildCount_tracksSnapshotRebuilds() {
        var now = 100_000L
        val selfId = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId, "Alpha") { now }

        // Initial creation builds the initial snapshot (1 rebuild)
        val initialCount = repo.graphRebuildCount.get()
        assertEquals(1L, initialCount)

        // Prior to WP-94g caching, each currentSnapshot() triggers buildSnapshot()
        val n = 10
        for (i in 1..n) {
            repo.currentSnapshot()
        }

        assertEquals("Before WP-94g caching, currentSnapshot triggers buildSnapshot each call", initialCount + n, repo.graphRebuildCount.get())
    }
}


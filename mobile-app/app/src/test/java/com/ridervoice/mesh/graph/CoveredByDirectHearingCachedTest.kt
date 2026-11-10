package com.ridervoice.mesh.graph

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.link.MeshEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import java.util.UUID

class CoveredByDirectHearingCachedTest {

    // Oracle: original uncached implementation from ForwardingEngine
    private fun coveredByDirectHearingOracle(
        edges: List<MeshEdge>,
        sourceId: String,
        previousHopId: String,
        now: Long
    ): Set<String> {
        val covered = HashSet<String>()
        for (e in edges) {
            if (!e.bidirectional || e.expiresAt <= now) continue
            if (e.from == sourceId || e.from == previousHopId) covered.add(e.to)
            if (e.to == sourceId || e.to == previousHopId) covered.add(e.from)
        }
        return covered
    }

    @Test
    fun matchesOracleOn200RandomGraphs() {
        val rng = Random(4242)
        val now = 50_000L

        for (graphIdx in 1..200) {
            val numNodes = 4 + rng.nextInt(6) // 4 to 9 nodes
            val nodeIds = List(numNodes) { UUID.randomUUID().toString() }
            val selfId = nodeIds[0]

            val repo = MeshGraphRepository(selfId = selfId, selfName = "Node0", clock = { now }, monotonicClock = { now })

            // Random connections
            for (i in 1 until numNodes) {
                val peerId = nodeIds[i]
                val isDirect = rng.nextBoolean()
                val neighborsOfPeer = (0 until numNodes).filter { it != i && rng.nextBoolean() }.map { nodeIds[it] }

                if (isDirect) {
                    val ep = MeshEndpoint("192.168.1.$i", MeshConfig.MESH_PORT)
                    repo.onHello(peerId, "Peer$i", hopCount = 0, endpoint = ep, neighborIds = neighborsOfPeer)
                } else {
                    val ep = MeshEndpoint("192.168.1.254", MeshConfig.MESH_PORT)
                    repo.onHello(peerId, "Peer$i", hopCount = 1, endpoint = ep, neighborIds = neighborsOfPeer)
                }
            }

            val view = repo.forwardingView()
            val snap = repo.currentSnapshot()

            val sourceIdx = rng.nextInt(numNodes)
            val prevHopIdx = rng.nextInt(numNodes)
            val sourceId = nodeIds[sourceIdx]
            val previousHopId = nodeIds[prevHopIdx]

            val oracleResult = coveredByDirectHearingOracle(snap.edges, sourceId, previousHopId, now)
            val cachedResult = view.coveredBy(sourceId, previousHopId, now)

            assertEquals("Graph $graphIdx: cached result must match oracle exactly", oracleResult, cachedResult)

            // Second lookup must return the exact same cached result
            val secondResult = view.coveredBy(sourceId, previousHopId, now)
            assertEquals("Second lookup must match cached", cachedResult, secondResult)
        }
    }
}


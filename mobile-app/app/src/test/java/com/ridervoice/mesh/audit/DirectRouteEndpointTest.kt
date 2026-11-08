package com.ridervoice.mesh.audit

import com.ridervoice.mesh.graph.MeshEdge
import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.graph.MeshNodeInfo
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.routing.RouteCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRouteEndpointTest {

    @Test
    fun edgeIncidentToSelf_withNullEndpoint_neverConsideredDirectRoute() {
        val self = "node-A"
        val nodeB = "node-B"
        val nodeC = "node-C"
        val now = 1000L
        val expiry = 10_000L

        // A heard B directly (endpoint != null)
        val edgeAB = MeshEdge(
            from = self,
            to = nodeB,
            endpoint = MeshEndpoint("192.168.49.12", 45454),
            lastSeenAt = now,
            expiresAt = expiry,
            bidirectional = true
        )

        // B heard C directly (endpoint != null)
        val edgeBC = MeshEdge(
            from = nodeB,
            to = nodeC,
            endpoint = MeshEndpoint("192.168.49.13", 45454),
            lastSeenAt = now,
            expiresAt = expiry,
            bidirectional = true
        )

        // Remote report claims C is connected to A, but A has no local direct endpoint for C (endpoint == null)
        val phantomEdgeCA = MeshEdge(
            from = nodeC,
            to = self,
            endpoint = null,
            lastSeenAt = now,
            expiresAt = expiry,
            bidirectional = true
        )

        val snapshot = MeshGraphSnapshot(
            selfId = self,
            nodes = mapOf(
                self to MeshNodeInfo(self, "Rider-A"),
                nodeB to MeshNodeInfo(nodeB, "Rider-B"),
                nodeC to MeshNodeInfo(nodeC, "Rider-C")
            ),
            edges = listOf(edgeAB, edgeBC, phantomEdgeCA),
            version = 1L
        )

        val routes = RouteCalculator.compute(snapshot, now)

        val routeToC = routes[nodeC]
        // C must NOT be considered direct, because A has no direct radio endpoint to C!
        assertFalse(
            "C must not be a direct route when edge incident to self has null endpoint",
            routeToC?.direct == true
        )

        // Instead, route to C must be relayed through B (hopCount 2)
        assertEquals("Route to C must be via B with hopCount 2", 2, routeToC?.hopCount)
        assertEquals("Next hop to C must be B", nodeB, routeToC?.nextHop)
    }

    @Test
    fun nodeWithOnlyNullEndpointEdgeToSelf_hasNoRoute() {
        val self = "node-A"
        val nodeC = "node-C"
        val now = 1000L
        val expiry = 10_000L

        // C claims to hear A, but A never heard C directly
        val phantomEdgeCA = MeshEdge(
            from = nodeC,
            to = self,
            endpoint = null,
            lastSeenAt = now,
            expiresAt = expiry,
            bidirectional = false
        )

        val snapshot = MeshGraphSnapshot(
            selfId = self,
            nodes = mapOf(
                self to MeshNodeInfo(self, "Rider-A"),
                nodeC to MeshNodeInfo(nodeC, "Rider-C")
            ),
            edges = listOf(phantomEdgeCA),
            version = 1L
        )

        val routes = RouteCalculator.compute(snapshot, now)
        assertNull("Node C with only null endpoint edge to self must have no route", routes[nodeC])
    }
}


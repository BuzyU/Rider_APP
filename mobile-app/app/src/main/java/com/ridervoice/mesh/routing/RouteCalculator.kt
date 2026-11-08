package com.ridervoice.mesh.routing

import com.ridervoice.mesh.graph.MeshEdge
import com.ridervoice.mesh.graph.MeshGraphSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RouteEntry(
    val destination: String,
    val hopCount: Int,
    val nextHop: String,
    val path: List<String>,
    val direct: Boolean,
    val expiresAt: Long,
    val since: Long = 0L
)

/**
 * Breadth-first routing. With at most six devices BFS is sufficient. Equal-cost ties are broken
 * deterministically: neighbors are expanded in lexicographic deviceId order and the first discovery wins.
 */
object RouteCalculator {
    fun compute(snapshot: MeshGraphSnapshot, now: Long): Map<String, RouteEntry> {
        val nonExpired = snapshot.edges.filter { it.expiresAt > now }
        val usable = nonExpired.filter { (it.from != snapshot.selfId && it.to != snapshot.selfId) || it.endpoint != null }
        val biEdges = usable.filter { it.bidirectional }
        val biRoutes = bfs(snapshot.selfId, biEdges)
        val allRoutes = bfs(snapshot.selfId, usable)

        val result = LinkedHashMap<String, RouteEntry>()
        for (dest in allRoutes.keys.sorted()) {
            result[dest] = biRoutes[dest] ?: allRoutes.getValue(dest)
        }
        return result
    }

    private fun bfs(self: String, edges: List<MeshEdge>): Map<String, RouteEntry> {
        val adj = HashMap<String, MutableList<Pair<String, Long>>>()
        for (e in edges) {
            adj.getOrPut(e.from) { ArrayList() }.add(e.to to e.expiresAt)
            adj.getOrPut(e.to) { ArrayList() }.add(e.from to e.expiresAt)
        }
        adj.values.forEach { l -> l.sortBy { it.first } }

        val parent = HashMap<String, String>()
        val parentExpiry = HashMap<String, Long>()
        val depth = HashMap<String, Int>().apply { put(self, 0) }
        val queue = ArrayDeque<String>().apply { add(self) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for ((v, exp) in adj[u].orEmpty()) {
                if (depth.containsKey(v)) continue
                depth[v] = depth.getValue(u) + 1
                parent[v] = u
                parentExpiry[v] = exp
                queue.add(v)
            }
        }

        val result = LinkedHashMap<String, RouteEntry>()
        for (dest in depth.keys.filter { it != self }.sorted()) {
            val path = ArrayList<String>()
            var cur = dest
            var expiry = Long.MAX_VALUE
            while (cur != self) {
                path.add(cur)
                expiry = minOf(expiry, parentExpiry.getValue(cur))
                cur = parent.getValue(cur)
            }
            path.add(self)
            path.reverse()
            result[dest] = RouteEntry(dest, path.size - 1, path[1], path, path.size == 2, expiry)
        }
        return result
    }
}

/**
 * Thread-safe routing table backed by immutable snapshots.
 * Routing is Breadth-First Search (BFS) over the link-state mesh graph, NOT distance-vector.
 * With at most 6 devices, BFS provides shortest paths with deterministic tie-breaking.
 */
class RoutingTable {
    private val _routes = MutableStateFlow<Map<String, RouteEntry>>(emptyMap())
    val routes: StateFlow<Map<String, RouteEntry>> = _routes.asStateFlow()
    private val sinceByKey = HashMap<Triple<String, String, Int>, Long>()

    @Synchronized
    fun recompute(snapshot: MeshGraphSnapshot, now: Long) {
        val fresh = RouteCalculator.compute(snapshot, now)
        val keep = HashMap<Triple<String, String, Int>, Long>()
        val out = fresh.mapValues { (_, r) ->
            val k = Triple(r.destination, r.nextHop, r.hopCount)
            val s = sinceByKey[k] ?: now
            keep[k] = s
            r.copy(since = s)
        }
        sinceByKey.clear()
        sinceByKey.putAll(keep)
        _routes.value = out
    }

    fun routeTo(id: String): RouteEntry? = _routes.value[id]
}

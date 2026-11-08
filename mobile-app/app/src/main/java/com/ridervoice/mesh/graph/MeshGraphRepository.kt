package com.ridervoice.mesh.graph

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.link.MeshEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device = vertex, direct wireless connection = edge. No ordered list of devices is ever stored. */
data class MeshEdge(
    val from: String,
    val to: String,
    /** Only known for edges incident to this device; remote edges have no usable endpoint. */
    val endpoint: MeshEndpoint?,
    val lastSeenAt: Long,
    val expiresAt: Long,
    val latencyMs: Long?,
    val bidirectional: Boolean
)

data class MeshNodeInfo(
    val deviceId: String,
    val displayName: String,
    val onlineIdTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshNodeInfo) return false
        if (deviceId != other.deviceId) return false
        if (displayName != other.displayName) return false
        if (onlineIdTag != null) {
            if (other.onlineIdTag == null) return false
            if (!onlineIdTag.contentEquals(other.onlineIdTag)) return false
        } else if (other.onlineIdTag != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (onlineIdTag?.contentHashCode() ?: 0)
        return result
    }
}

/** Immutable view of the graph as known by one device. */
data class MeshGraphSnapshot(
    val selfId: String,
    val nodes: Map<String, MeshNodeInfo>,
    val edges: List<MeshEdge>,
    val version: Long
) {
    fun neighborsOf(id: String): List<String> =
        edges.filter { it.from == id || it.to == id }
            .map { if (it.from == id) it.to else it.from }
            .distinct().sorted()
}

data class DirectNeighbor(val deviceId: String, val name: String, val endpoint: MeshEndpoint, val lastSeen: Long)

internal fun isStaleReport(prevEpoch: Int, prevSeq: Int, epoch: Int, seq: Int): Boolean =
    prevSeq != 0 && seq != 0 && prevEpoch == epoch && (seq - prevSeq) < 0

class MeshGraphRepository(
    private val selfId: String,
    private val selfName: String,
    private val selfOnlineTagProvider: () -> ByteArray? = { null },
    private val clock: () -> Long
) {
    constructor(
        selfId: String,
        selfName: String,
        clock: () -> Long
    ) : this(selfId, selfName, { null }, clock)

    private class Neighbor(var name: String, var endpoint: MeshEndpoint, var lastSeen: Long, var onlineIdTag: ByteArray? = null)
    private class Report(var name: String, var neighbors: Set<String>, var lastSeen: Long, var sequence: Int = 0, var epoch: Int = 0, var onlineIdTag: ByteArray? = null)

    private val neighbors = LinkedHashMap<String, Neighbor>()
    private val reports = HashMap<String, Report>()
    private var version = 0L

    private val _snapshots = MutableStateFlow(buildSnapshot())
    val snapshots: StateFlow<MeshGraphSnapshot> = _snapshots.asStateFlow()

    /**
     * @param hopCount 0 when received directly from [sourceId]; the sender is then a direct neighbor.
     * @param endpoint where the frame came from (only meaningful when hopCount == 0).
     * @return true if the graph structure or a neighbor endpoint changed.
     */
    @Synchronized
    fun onHello(
        sourceId: String,
        name: String,
        hopCount: Int,
        endpoint: MeshEndpoint,
        neighborIds: Collection<String>,
        sequence: Int = 0,
        epoch: Int = 0,
        onlineIdTag: ByteArray? = null
    ): Boolean {
        if (sourceId == selfId) return false
        val now = clock()
        var changed = false
        if (hopCount == 0) {
            val n = neighbors[sourceId]
            if (n == null) {
                neighbors[sourceId] = Neighbor(name, endpoint, now, onlineIdTag); changed = true
            } else {
                if (n.endpoint != endpoint || n.name != name || (onlineIdTag != null && !onlineIdTag.contentEquals(n.onlineIdTag))) changed = true
                n.endpoint = endpoint; n.name = name; n.lastSeen = now; if (onlineIdTag != null) n.onlineIdTag = onlineIdTag
            }
        }
        val set = neighborIds.filter { it != sourceId }.toSet()
        val r = reports[sourceId]
        if (r == null) {
            reports[sourceId] = Report(name, set, now, sequence, epoch, onlineIdTag)
            changed = true
        } else {
            val isStale = isStaleReport(r.epoch, r.sequence, epoch, sequence)
            if (!isStale) {
                if (r.neighbors != set || r.name != name || r.epoch != epoch || (onlineIdTag != null && !onlineIdTag.contentEquals(r.onlineIdTag))) changed = true
                r.neighbors = set
                r.name = name
                r.lastSeen = now
                r.epoch = epoch
                if (onlineIdTag != null) r.onlineIdTag = onlineIdTag
                if (sequence != 0) r.sequence = sequence
            }
        }
        if (changed) {
            publish()
        } else {
            // Refresh edge lastSeenAt and expiresAt timestamps in the published snapshot
            // without incrementing the structural graph version.
            _snapshots.value = buildSnapshot()
        }
        return changed
    }

    @Synchronized
    fun onBye(sourceId: String): Boolean {
        val a = neighbors.remove(sourceId) != null
        val b = reports.remove(sourceId) != null
        if (a || b) publish()
        return a || b
    }

    /** Drops timed-out neighbors and reports. Returns true if anything changed. */
    @Synchronized
    fun expire(): Boolean {
        val now = clock()
        var changed = false
        val dead = neighbors.filter { now - it.value.lastSeen > MeshConfig.NEIGHBOR_TIMEOUT_MS }.keys.toList()
        for (id in dead) {
            neighbors.remove(id)
            // Its last report is no longer trustworthy; if it is still alive via another path, the next
            // flooded HELLO restores it within one interval.
            reports.remove(id)
            changed = true
        }
        val staleReports = reports.filter { now - it.value.lastSeen > MeshConfig.REMOTE_REPORT_TIMEOUT_MS }.keys.toList()
        for (id in staleReports) { reports.remove(id); changed = true }
        if (changed) publish()
        return changed
    }

    @Synchronized
    fun directNeighbors(): List<DirectNeighbor> =
        neighbors.entries.sortedBy { it.key }.map { DirectNeighbor(it.key, it.value.name, it.value.endpoint, it.value.lastSeen) }

    @Synchronized
    fun directNeighborIds(): List<String> = neighbors.keys.sorted()

    @Synchronized
    fun neighbor(id: String): DirectNeighbor? =
        neighbors[id]?.let { DirectNeighbor(id, it.name, it.endpoint, it.lastSeen) }

    @Synchronized
    fun reset() {
        neighbors.clear(); reports.clear(); publish()
    }

    @Synchronized
    fun currentSnapshot(): MeshGraphSnapshot = buildSnapshot()

    private fun publish() { version++; _snapshots.value = buildSnapshot() }

    private fun buildSnapshot(): MeshGraphSnapshot {
        val nodes = LinkedHashMap<String, MeshNodeInfo>()
        nodes[selfId] = MeshNodeInfo(selfId, selfName, selfOnlineTagProvider())
        neighbors.forEach { (id, n) -> nodes[id] = MeshNodeInfo(id, n.name, n.onlineIdTag) }
        reports.forEach { (id, r) ->
            val existing = nodes[id]
            val tag = r.onlineIdTag ?: existing?.onlineIdTag
            nodes[id] = MeshNodeInfo(id, r.name, tag)
            r.neighbors.forEach { nid -> if (nid != selfId && !nodes.containsKey(nid)) nodes[nid] = MeshNodeInfo(nid, nid.take(8)) }
        }

        // reporter -> reported neighbor, with freshness
        class Claim(val lastSeen: Long, val expiresAt: Long, val endpoint: MeshEndpoint?)
        val claims = HashMap<Pair<String, String>, Claim>()
        neighbors.forEach { (id, n) ->
            claims[selfId to id] = Claim(n.lastSeen, n.lastSeen + MeshConfig.NEIGHBOR_TIMEOUT_MS, n.endpoint)
        }
        reports.forEach { (rid, r) ->
            r.neighbors.forEach { nid ->
                if (rid != selfId) claims[rid to nid] = Claim(r.lastSeen, r.lastSeen + MeshConfig.REMOTE_REPORT_TIMEOUT_MS, null)
            }
        }

        val seen = HashSet<Pair<String, String>>()
        val edges = ArrayList<MeshEdge>()
        for ((k, c) in claims.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val key = if (k.first <= k.second) k else k.second to k.first
            if (!seen.add(key)) continue
            val reverse = claims[k.second to k.first]
            val ep = c.endpoint ?: reverse?.endpoint
            edges.add(
                MeshEdge(
                    from = key.first, to = key.second, endpoint = ep,
                    lastSeenAt = maxOf(c.lastSeen, reverse?.lastSeen ?: 0L),
                    expiresAt = maxOf(c.expiresAt, reverse?.expiresAt ?: 0L),
                    latencyMs = null,
                    bidirectional = reverse != null
                )
            )
        }
        return MeshGraphSnapshot(selfId, nodes, edges, version)
    }
}


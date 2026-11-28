package com.ridervoice.mesh.topology

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.link.StarGroupOwnerSelector
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Computes an orientation-independent Star or linear Bus/Chain ("daisy-hop") topology plan
 * over `n <= MAX_DEVICES` riders using local tangent-plane projection and an exact
 * minimum-length Hamiltonian path search (`n! <= 720`).
 */
object TopologyEngine {

    fun computePlan(
        meshParticipants: List<String>,
        positions: Map<String, PositionFix>,
        dualP2pCapableIds: Set<String>,
        currentHostId: String?,
        electionEpoch: Int = 0,
        starRangeThresholdM: Double = MeshConfig.STAR_RANGE_THRESHOLD_M,
        preferChainMode: Boolean = false
    ): TopologyPlan {
        val validPositioned = meshParticipants.filter { positions[it]?.isAccurate() == true }
        val positionedCoords = validPositioned.associateWith {
            val fix = positions[it]!!
            fix.latDeg to fix.lonDeg
        }
        val starGoId = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = meshParticipants,
                hostUserId = currentHostId,
                positions = positionedCoords
            )
        )

        if (validPositioned.size < 3) {
            return starPlan(meshParticipants, starGoId, electionEpoch)
        }

        val lat0 = validPositioned.map { positions[it]!!.latDeg }.average()
        val lon0 = validPositioned.map { positions[it]!!.lonDeg }.average()
        val cosLat0 = cos(Math.toRadians(lat0))
        val projected = validPositioned.associateWith { id ->
            val fix = positions[id]!!
            val xM = (fix.lonDeg - lon0) * 111_320.0 * cosLat0
            val yM = (fix.latDeg - lat0) * 111_132.0
            xM to yM
        }

        var maxSpreadM = 0.0
        for (i in validPositioned.indices) {
            val (x1, y1) = projected[validPositioned[i]]!!
            for (j in i + 1 until validPositioned.size) {
                val (x2, y2) = projected[validPositioned[j]]!!
                val d = hypot(x2 - x1, y2 - y1)
                if (d > maxSpreadM) maxSpreadM = d
            }
        }
        if (!preferChainMode && maxSpreadM <= starRangeThresholdM) {
            return starPlan(meshParticipants, starGoId, electionEpoch)
        }

        val bestPath = findMinLengthHamiltonianPath(validPositioned, projected)
        val oriented = if (compareIds(bestPath.first(), bestPath.last()) > 0) {
            bestPath.reversed()
        } else {
            bestPath
        }

        val interior = oriented.subList(1, oriented.size - 1)
        if (interior.size > MeshConfig.MAX_CHAIN_HOPS || interior.any { it !in dualP2pCapableIds }) {
            return starPlan(meshParticipants, starGoId, electionEpoch)
        }

        val roles = LinkedHashMap<String, TopologyRole>(meshParticipants.size)
        for ((idx, memberId) in oriented.withIndex()) {
            roles[memberId] = if (idx == 0 || idx == oriented.lastIndex) {
                TopologyRole.CHAIN_LEAF
            } else {
                TopologyRole.CHAIN_BRIDGE
            }
        }
        for (memberId in meshParticipants) {
            roles.putIfAbsent(memberId, TopologyRole.STAR_CLIENT)
        }

        val rosterIndexById = meshParticipants.withIndex().associate { it.value to it.index }
        val segments = ArrayList<ChainSegment>(oriented.size - 1)
        for (i in 0 until oriented.size - 1) {
            val goId = oriented[i]
            val clientId = oriented[i + 1]
            segments.add(
                ChainSegment(
                    goMemberId = goId,
                    clientMemberId = clientId,
                    goRosterIdx = rosterIndexById[goId] ?: i,
                    clientRosterIdx = rosterIndexById[clientId] ?: (i + 1)
                )
            )
        }

        return TopologyPlan(
            isChainMode = true,
            orderedChain = oriented,
            roles = roles,
            segments = segments,
            starGoId = starGoId,
            electionEpoch = electionEpoch
        )
    }

    private fun starPlan(
        meshParticipants: List<String>,
        starGoId: String?,
        electionEpoch: Int
    ): TopologyPlan {
        val roles = meshParticipants.associateWith { id ->
            if (id == starGoId) TopologyRole.STAR_GO else TopologyRole.STAR_CLIENT
        }
        return TopologyPlan(
            isChainMode = false,
            orderedChain = emptyList(),
            roles = roles,
            segments = emptyList(),
            starGoId = starGoId,
            electionEpoch = electionEpoch
        )
    }

    private fun compareIds(a: String, b: String): Int =
        ConvoyHash.compareUtf8Bytes(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun findMinLengthHamiltonianPath(
        nodes: List<String>,
        projected: Map<String, Pair<Double, Double>>
    ): List<String> {
        val n = nodes.size
        val used = BooleanArray(n)
        val current = ArrayList<String>(n)
        var bestLength = Double.POSITIVE_INFINITY
        var bestPath: List<String> = nodes

        fun totalPathLength(path: List<String>): Double {
            var sum = 0.0
            for (i in 0 until path.size - 1) {
                val (x1, y1) = projected[path[i]]!!
                val (x2, y2) = projected[path[i + 1]]!!
                sum += hypot(x2 - x1, y2 - y1)
            }
            return sum
        }

        fun comparePaths(a: List<String>, b: List<String>): Int {
            for (i in a.indices) {
                val c = compareIds(a[i], b[i])
                if (c != 0) return c
            }
            return 0
        }

        fun search(depth: Int, accumLength: Double) {
            if (accumLength > bestLength + 1e-6) return
            if (depth == n) {
                val len = totalPathLength(current)
                val normalized = if (compareIds(current.first(), current.last()) > 0) {
                    current.reversed()
                } else {
                    current.toList()
                }
                if (len < bestLength - 1e-6 ||
                    (kotlin.math.abs(len - bestLength) <= 1e-6 && comparePaths(normalized, bestPath) < 0)
                ) {
                    bestLength = len
                    bestPath = current.toList()
                }
                return
            }
            for (i in 0 until n) {
                if (used[i]) continue
                used[i] = true
                val nextId = nodes[i]
                val stepLen = if (current.isEmpty()) {
                    0.0
                } else {
                    val (x1, y1) = projected[current.last()]!!
                    val (x2, y2) = projected[nextId]!!
                    hypot(x2 - x1, y2 - y1)
                }
                current.add(nextId)
                search(depth + 1, accumLength + stepLen)
                current.removeAt(current.size - 1)
                used[i] = false
            }
        }

        search(0, 0.0)
        return bestPath
    }
}

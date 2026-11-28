package com.ridervoice.mesh.link

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic P2P Group Owner selector across the convoy.
 *
 * Ensures every phone independently elects the same capable rider (API 29+ AUTO_P2P),
 * avoids churning a working group on online host leave/transfer or mid-offline-voice,
 * and promotes the next canonical survivor on GO death.
 */
object StarGroupOwnerSelector {

    data class Input(
        val meshParticipants: List<String>,
        val hostUserId: String?,
        val previousGoId: String? = null,
        val isP2pConnected: Boolean = false,
        val isLiveOfflineVoiceActive: Boolean = false,
        val excludedGoIds: Set<String> = emptySet(),
        val positions: Map<String, Pair<Double, Double>> = emptyMap(),
        val forceReelect: Boolean = false
    )

    fun selectGoId(input: Input): String? {
        val eligible = input.meshParticipants.filter { it !in input.excludedGoIds }
        if (eligible.isEmpty()) return null

        val prevEligible = input.previousGoId != null && input.previousGoId in eligible
        if (!input.forceReelect && prevEligible) {
            if (input.isLiveOfflineVoiceActive || input.isP2pConnected) {
                return input.previousGoId
            }
        }

        val positionedEligible = eligible.filter { input.positions.containsKey(it) }
        if (positionedEligible.size >= 2) {
            var bestId: String = eligible.first()
            var bestMaxDist = Double.POSITIVE_INFINITY
            var bestSumDist = Double.POSITIVE_INFINITY
            var bestIsHost = false
            var bestIdx = Int.MAX_VALUE

            for ((idx, candidate) in eligible.withIndex()) {
                val cPos = input.positions[candidate] ?: continue
                var maxDist = 0.0
                var sumDist = 0.0
                for (peer in positionedEligible) {
                    if (peer == candidate) continue
                    val pPos = input.positions[peer] ?: continue
                    val d = distanceMeters(cPos.first, cPos.second, pPos.first, pPos.second)
                    if (d > maxDist) maxDist = d
                    sumDist += d
                }
                val isHost = candidate == input.hostUserId
                val better = when {
                    maxDist < bestMaxDist - 1e-6 -> true
                    maxDist > bestMaxDist + 1e-6 -> false
                    sumDist < bestSumDist - 1e-6 -> true
                    sumDist > bestSumDist + 1e-6 -> false
                    isHost != bestIsHost -> isHost
                    else -> idx < bestIdx
                }
                if (better) {
                    bestId = candidate
                    bestMaxDist = maxDist
                    bestSumDist = sumDist
                    bestIsHost = isHost
                    bestIdx = idx
                }
            }
            return bestId
        }

        if (input.hostUserId != null && input.hostUserId in eligible) {
            return input.hostUserId
        }
        return eligible.first()
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }
}

package com.ridervoice.mesh.topology

enum class TopologyRole {
    STAR_GO,
    STAR_CLIENT,
    CHAIN_BRIDGE,
    CHAIN_LEAF
}

/**
 * Quantized GNSS position fix exchanged over mesh CONTROL packets (`0x81`).
 */
data class PositionFix(
    val memberId: String,
    val timestampSec: Int,
    val latE6: Int,
    val lonE6: Int,
    val accuracyDecimeters: Int
) {
    val latDeg: Double get() = latE6 / 1_000_000.0
    val lonDeg: Double get() = lonE6 / 1_000_000.0
    val accuracyM: Double
        get() = if (accuracyDecimeters >= UNKNOWN_ACCURACY_DECIMETERS) {
            Double.POSITIVE_INFINITY
        } else {
            accuracyDecimeters / 10.0
        }

    fun isAccurate(maxAccuracyM: Double = DEFAULT_MAX_ACCURACY_M): Boolean =
        accuracyM <= maxAccuracyM

    companion object {
        const val UNKNOWN_ACCURACY_DECIMETERS = 65535
        const val DEFAULT_MAX_ACCURACY_M = 30.0
    }
}

/**
 * Adjacent Wi-Fi Direct bus segment between a Group Owner and its client.
 */
data class ChainSegment(
    val goMemberId: String,
    val clientMemberId: String,
    val goRosterIdx: Int,
    val clientRosterIdx: Int
)

/**
 * Deterministic Star or multi-hop Bus/Chain topology plan computed identically on every phone.
 */
data class TopologyPlan(
    val isChainMode: Boolean,
    val orderedChain: List<String>,
    val roles: Map<String, TopologyRole>,
    val segments: List<ChainSegment>,
    val starGoId: String?,
    val electionEpoch: Int
)

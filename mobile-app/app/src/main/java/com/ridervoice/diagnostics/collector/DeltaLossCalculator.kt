package com.ridervoice.diagnostics.collector

/**
 * Computes interval packet loss percentage for remote WebRTC tracks.
 * Handles cumulative counter resets, negative WebRTC packetsLost deltas,
 * and clamps interval loss percentage at >= 0.0%.
 */
class DeltaLossCalculator {

    data class TrackBaseline(
        val lastPacketsReceived: Long,
        val lastPacketsLost: Long
    )

    private val trackBaselines = mutableMapOf<String, TrackBaseline>()

    /**
     * Calculates interval loss percentage for a remote track based on cumulative counters.
     * Returns a percentage value [0.0, 100.0] clamped at >= 0.0, or null if baseline was just established.
     * Handles counter resets (reconnects/ICE restarts) gracefully.
     */
    @Synchronized
    fun calculateIntervalLoss(trackKey: String, currentReceived: Long, currentLost: Long): Double? {
        val previous = trackBaselines[trackKey]
        trackBaselines[trackKey] = TrackBaseline(currentReceived, currentLost)

        if (previous == null) {
            return null
        }

        // Detect counter reset (e.g. ICE restart or new connection)
        if (currentReceived < previous.lastPacketsReceived) {
            return null
        }

        val deltaReceived = currentReceived - previous.lastPacketsReceived
        val deltaLost = currentLost - previous.lastPacketsLost

        // WebRTC packetsLost can decrease due to late out-of-order packets.
        // Clamp interval loss at >= 0.0.
        val effectiveDeltaLost = if (deltaLost < 0) 0L else deltaLost

        val totalExpected = deltaReceived + effectiveDeltaLost
        if (totalExpected <= 0) {
            return 0.0
        }

        val lossPct = (effectiveDeltaLost.toDouble() / totalExpected.toDouble()) * 100.0
        return lossPct.coerceIn(0.0, 100.0)
    }

    @Synchronized
    fun resetTrack(trackKey: String) {
        trackBaselines.remove(trackKey)
    }

    @Synchronized
    fun clear() {
        trackBaselines.clear()
    }
}

package com.ridervoice.mesh

/**
 * Per-room offline mesh configuration. Created in the lobby,
 * stored in ActiveRideRepository, and cleared when the ride ends.
 */
data class RoomMeshConfig(
    val offlineMeshEnabled: Boolean = false,
    val automaticFallbackEnabled: Boolean = false,
    val warmStandbyEnabled: Boolean = false,
    val configCompleted: Boolean = false  // true when all config steps done
) {
    companion object {
        /** Default: offline disabled for this room */
        val DISABLED = RoomMeshConfig(
            offlineMeshEnabled = false,
            configCompleted = true
        )
    }
}


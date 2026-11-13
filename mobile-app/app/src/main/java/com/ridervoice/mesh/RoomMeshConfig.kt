package com.ridervoice.mesh

import com.ridervoice.mesh.offline.OfflineConfigStatus
import com.ridervoice.mesh.offline.OfflineConfigStep
import com.ridervoice.mesh.offline.StepResult

/**
 * Per-room offline mesh configuration. Created in the lobby,
 * stored in ActiveRideRepository, and cleared when the ride ends.
 * Defaults to ON per Decision D10.
 */
data class RoomMeshConfig(
    val offlineMeshEnabled: Boolean = true,
    val automaticFallbackEnabled: Boolean = true,
    val warmStandbyEnabled: Boolean = true,
    val configCompleted: Boolean = false,
    val status: OfflineConfigStatus = OfflineConfigStatus.NOT_STARTED,
    val stepResults: Map<OfflineConfigStep, StepResult> = emptyMap(),
    val optOut: Boolean = false
) {
    companion object {
        /** Default: offline disabled for this room */
        val DISABLED = RoomMeshConfig(
            offlineMeshEnabled = false,
            automaticFallbackEnabled = false,
            warmStandbyEnabled = false,
            configCompleted = true,
            status = OfflineConfigStatus.OPTED_OUT,
            optOut = true
        )

        /** Default: offline enabled for new rooms (Decision D10) */
        val DEFAULT = RoomMeshConfig(
            offlineMeshEnabled = true,
            automaticFallbackEnabled = true,
            warmStandbyEnabled = true,
            configCompleted = false,
            status = OfflineConfigStatus.NOT_STARTED,
            optOut = false
        )
    }
}

package com.ridervoice.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Pure mesh-layer snapshot for mirroring a room's per-room offline backup settings
 * into [MeshRuntimeSettings] without coupling `mesh/` to online state classes.
 */
data class RoomMeshMirrorSnapshot(
    val isLive: Boolean,
    val offlineMeshEnabled: Boolean = true,
    val optOut: Boolean = false,
    val automaticFallbackEnabled: Boolean = true,
    val warmStandbyEnabled: Boolean = true
)

/**
 * WP-158: Mirrors the active room's per-room [RoomMeshConfig] into [MeshRuntimeSettings] ONLY
 * while [RoomMeshMirrorSnapshot.isLive] is true, resetting all flags to false
 * whenever there is no active ride or the phase is LOBBY/ENDING.
 */
object RoomMeshFlagMirror {

    fun sync(snapshot: RoomMeshMirrorSnapshot?, settings: MeshRuntimeSettings) {
        if (snapshot != null && snapshot.isLive) {
            val enableMesh = snapshot.offlineMeshEnabled && !snapshot.optOut
            settings.setOfflineMeshEnabled(enableMesh)
            settings.setAutomaticFallbackEnabled(enableMesh && snapshot.automaticFallbackEnabled)
            settings.setWarmStandbyEnabled(enableMesh && snapshot.warmStandbyEnabled)
        } else {
            settings.setOfflineMeshEnabled(false)
            settings.setAutomaticFallbackEnabled(false)
            settings.setWarmStandbyEnabled(false)
        }
    }

    fun observe(
        snapshots: Flow<RoomMeshMirrorSnapshot?>,
        initialSnapshot: RoomMeshMirrorSnapshot?,
        settings: MeshRuntimeSettings,
        scope: CoroutineScope
    ): Job {
        sync(initialSnapshot, settings)
        return scope.launch {
            snapshots.collect { snapshot ->
                sync(snapshot, settings)
            }
        }
    }
}

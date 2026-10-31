package com.ridervoice.mesh

/**
 * Pure decision logic combining the build-time flag with runtime user settings.
 * Every offline entry point must consult this before doing any work.
 * All inputs default to off.
 */
object OfflineMeshGate {

    /** Offline mesh may run only if both the build flag and the user setting are on. */
    fun isOfflineAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = false
    ): Boolean = buildFlag && offlineMeshEnabled

    /** Automatic online-to-offline fallback additionally requires its own setting. */
    fun isAutomaticFallbackAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = false,
        automaticFallbackEnabled: Boolean = false
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && automaticFallbackEnabled

    /** Warm standby (discovery while online) additionally requires its own setting. */
    fun isWarmStandbyAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = false,
        warmStandbyEnabled: Boolean = false
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && warmStandbyEnabled
}


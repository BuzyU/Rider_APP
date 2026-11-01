package com.ridervoice.mesh

/**
 * Pure decision logic combining the build-time flag with runtime user settings.
 * Every offline entry point must consult this before doing any work.
 * All inputs default to off.
 */
object OfflineMeshGate {
    @Volatile private var settings: MeshRuntimeSettings = MeshRuntimeSettings.AllOff
    fun install(s: MeshRuntimeSettings) { settings = s }

    /** Returns whether the build-level gate allows offline mesh (independent of user preferences). */
    fun isBuildGateOpen(): Boolean = FeatureFlags.OFFLINE_MESH_ENABLED

    /** Offline mesh may run only if both the build flag and the user setting are on. */
    fun isOfflineAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled
    ): Boolean = buildFlag && offlineMeshEnabled

    /** Automatic online-to-offline fallback additionally requires its own setting. */
    fun isAutomaticFallbackAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled,
        automaticFallbackEnabled: Boolean = settings.automaticFallbackEnabled
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && automaticFallbackEnabled

    /** Warm standby (discovery while online) additionally requires its own setting. */
    fun isWarmStandbyAllowed(
        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled,
        warmStandbyEnabled: Boolean = settings.warmStandbyEnabled
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && warmStandbyEnabled
}

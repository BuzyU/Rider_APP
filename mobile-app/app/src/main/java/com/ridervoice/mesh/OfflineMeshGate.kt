package com.ridervoice.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GateDecision {
    ENABLED,
    DISABLED_BY_BUILD,
    DISABLED_BY_USER
}

/**
 * Pure decision logic combining the build-time flag with runtime user settings.
 * Every offline entry point must consult this before doing any work.
 * All inputs default to off.
 */
object OfflineMeshGate {
    @Volatile private var settings: MeshRuntimeSettings = MeshRuntimeSettings.AllOff
    val settingsFlow: StateFlow<MeshSettingsSnapshot> get() = settings.settingsFlow

    fun install(s: MeshRuntimeSettings) { settings = s }

    /** Returns whether the build-level gate allows offline mesh (independent of user preferences). */
    fun isBuildGateOpen(): Boolean = FeatureFlags.OFFLINE_MESH_ENABLED

    fun decision(
        buildFlag: Boolean = isBuildGateOpen(),
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled
    ): GateDecision {
        return when {
            !buildFlag -> GateDecision.DISABLED_BY_BUILD
            !offlineMeshEnabled -> GateDecision.DISABLED_BY_USER
            else -> GateDecision.ENABLED
        }
    }

    /** Offline mesh may run only if both the build flag and the user setting are on. */
    fun isOfflineAllowed(
        buildFlag: Boolean = isBuildGateOpen(),
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled
    ): Boolean = decision(buildFlag, offlineMeshEnabled) == GateDecision.ENABLED

    /** Automatic online-to-offline fallback additionally requires its own setting. */
    fun isAutomaticFallbackAllowed(
        buildFlag: Boolean = isBuildGateOpen(),
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled,
        automaticFallbackEnabled: Boolean = settings.automaticFallbackEnabled
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && automaticFallbackEnabled

    /** Warm standby (discovery while online) additionally requires its own setting. */
    fun isWarmStandbyAllowed(
        buildFlag: Boolean = isBuildGateOpen(),
        offlineMeshEnabled: Boolean = settings.offlineMeshEnabled,
        warmStandbyEnabled: Boolean = settings.warmStandbyEnabled
    ): Boolean = isOfflineAllowed(buildFlag, offlineMeshEnabled) && warmStandbyEnabled
}

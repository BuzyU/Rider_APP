package com.ridervoice.mesh

import androidx.annotation.VisibleForTesting
import com.ridervoice.BuildConfig

/**
 * Feature switch for the offline mesh intercom.
 *
 * Driven by [BuildConfig.OFFLINE_MESH_BUILD_ENABLED] (defaults to true in debug and release builds
 * unless overridden with -PenableOfflineMeshInRelease=false), combined with an in-app emergency
 * kill switch [localKillSwitch] and an internal debug/test override [localOverride].
 *
 * In release builds, [localOverride] is ignored and the gate follows [BuildConfig.OFFLINE_MESH_BUILD_ENABLED] && ![localKillSwitch].
 *
 * While this evaluates to `false`, the offline subsystem must not open sockets, start discovery,
 * build a graph, create AudioRecord/AudioTrack, request offline permissions, or start foreground services.
 */
object FeatureFlags {
    /** Emergency in-app kill switch (not remote yet; in-app/diagnostic control). Defaults to false (offline mesh allowed when build permits). When true, kills offline mesh unconditionally. */
    @Volatile
    var localKillSwitch: Boolean = false

    /**
     * Local override for testing and debug diagnostics.
     * Internal and only respected in debug builds; release builds unconditionally ignore it.
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var localOverride: Boolean? = null

    val OFFLINE_MESH_ENABLED: Boolean
        get() {
            if (!BuildConfig.DEBUG) {
                return BuildConfig.OFFLINE_MESH_BUILD_ENABLED && !localKillSwitch
            }
            if (localKillSwitch) return false
            return localOverride ?: BuildConfig.OFFLINE_MESH_BUILD_ENABLED
        }
}

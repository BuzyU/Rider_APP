package com.ridervoice.mesh

import androidx.annotation.VisibleForTesting
import com.ridervoice.BuildConfig

/**
 * Feature switch for the offline mesh intercom.
 *
 * Driven by [BuildConfig.OFFLINE_MESH_BUILD_ENABLED] (true in debug, false in release builds),
 * combined with an in-app emergency kill switch [localKillSwitch] and an internal debug/test override [localOverride].
 *
 * In release builds, [localOverride] is ignored and the feature is unconditionally false.
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
            if (localKillSwitch) return false
            // In release builds, localOverride is ignored; mesh is hard disabled
            if (!BuildConfig.DEBUG && !BuildConfig.OFFLINE_MESH_BUILD_ENABLED) {
                return false
            }
            return localOverride ?: BuildConfig.OFFLINE_MESH_BUILD_ENABLED
        }
}

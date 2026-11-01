package com.ridervoice.mesh

import com.ridervoice.BuildConfig

/**
 * Feature switch for the offline mesh intercom.
 *
 * Driven by [BuildConfig.OFFLINE_MESH_BUILD_ENABLED] (true in debug, false in release builds),
 * combined with a remote kill switch and local debug override for testing/emergency control.
 *
 * While this evaluates to `false`, the offline subsystem must not open sockets, start discovery,
 * build a graph, create AudioRecord/AudioTrack, request offline permissions, or start foreground services.
 */
object FeatureFlags {
    /** Remote kill switch to shut down mesh fleet-wide regardless of build type. */
    @Volatile
    var remoteKillSwitch: Boolean = false

    /** Local override for testing or emergency runtime diagnostics. Null means use BuildConfig default. */
    @Volatile
    var localOverride: Boolean? = null

    val OFFLINE_MESH_ENABLED: Boolean
        get() = if (remoteKillSwitch) {
            false
        } else {
            localOverride ?: BuildConfig.OFFLINE_MESH_BUILD_ENABLED
        }
}

package com.ridervoice.mesh

/**
 * Build-time switch for the offline mesh intercom.
 *
 * While this is `false` the offline subsystem must not open sockets, start discovery,
 * build a graph, create AudioRecord/AudioTrack, request offline permissions, or register
 * callbacks. The online LiveKit path never reads this flag.
 */
object FeatureFlags {
    const val OFFLINE_MESH_ENABLED: Boolean = false
}


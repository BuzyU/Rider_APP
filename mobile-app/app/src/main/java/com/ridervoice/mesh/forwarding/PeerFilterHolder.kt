package com.ridervoice.mesh.forwarding

/**
 * Global holder for debug-only peer filtering.
 * Set exclusively by MeshDebugReceiver in debug builds or test harnesses to simulate multi-hop topologies.
 * In release builds, this remains null.
 */
object PeerFilterHolder {
    @Volatile
    var port: PeerFilterPort? = null

    fun reset() {
        port = null
    }
}


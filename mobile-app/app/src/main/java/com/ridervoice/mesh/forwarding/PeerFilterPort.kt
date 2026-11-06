package com.ridervoice.mesh.forwarding

/**
 * Debug-only port allowing test harnesses or debug receivers to filter packets by previous hop.
 * In production release builds, this port is null so zero filtering backdoor logic exists in forwarding.
 */
fun interface PeerFilterPort {
    fun shouldAccept(previousHopId: String): Boolean
}


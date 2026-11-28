package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshConfig

/**
 * Computes direct-neighbor and remote-report timeouts for the active [MeshCadence]
 * with a bounded tighten grace window when tightening from STANDBY to ACTIVE/BOOST.
 */
class CadenceTimeoutPolicy(
    private val monotonicClock: () -> Long,
    private val graceMs: Long = MeshConfig.CADENCE_TIGHTEN_GRACE_MS
) {
    private var currentCadence: MeshCadence = MeshCadence.STANDBY
    private var effectiveDirectMs: Long = directFor(MeshCadence.STANDBY)
    private var effectiveRemoteMs: Long = remoteFor(MeshCadence.STANDBY)
    private var heldDirectMs: Long = effectiveDirectMs
    private var heldRemoteMs: Long = effectiveRemoteMs
    private var graceUntilMs: Long = 0L

    @Synchronized
    fun onCadenceChanged(newCadence: MeshCadence) {
        val prevDirect = directTimeoutMs()
        val prevRemote = remoteReportTimeoutMs()
        val nextDirect = directFor(newCadence)
        val nextRemote = remoteFor(newCadence)
        currentCadence = newCadence
        effectiveDirectMs = nextDirect
        effectiveRemoteMs = nextRemote
        if (nextDirect < prevDirect || nextRemote < prevRemote) {
            heldDirectMs = maxOf(nextDirect, prevDirect)
            heldRemoteMs = maxOf(nextRemote, prevRemote)
            graceUntilMs = monotonicClock() + graceMs
        } else {
            graceUntilMs = 0L
        }
    }

    @Synchronized
    fun directTimeoutMs(): Long =
        if (monotonicClock() < graceUntilMs) maxOf(effectiveDirectMs, heldDirectMs)
        else effectiveDirectMs

    @Synchronized
    fun remoteReportTimeoutMs(): Long =
        if (monotonicClock() < graceUntilMs) maxOf(effectiveRemoteMs, heldRemoteMs)
        else effectiveRemoteMs

    companion object {
        fun directFor(c: MeshCadence): Long = when (c) {
            MeshCadence.BOOST -> MeshConfig.BOOST_NEIGHBOR_TIMEOUT_MS
            MeshCadence.ACTIVE -> MeshConfig.ACTIVE_NEIGHBOR_TIMEOUT_MS
            MeshCadence.STANDBY -> MeshConfig.STANDBY_NEIGHBOR_TIMEOUT_MS
        }

        fun remoteFor(c: MeshCadence): Long = when (c) {
            MeshCadence.BOOST -> MeshConfig.BOOST_REMOTE_REPORT_TIMEOUT_MS
            MeshCadence.ACTIVE -> MeshConfig.ACTIVE_REMOTE_REPORT_TIMEOUT_MS
            MeshCadence.STANDBY -> MeshConfig.STANDBY_REMOTE_REPORT_TIMEOUT_MS
        }
    }
}

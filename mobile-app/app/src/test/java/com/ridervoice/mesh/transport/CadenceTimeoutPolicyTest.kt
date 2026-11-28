package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceTimeoutPolicyTest {

    @Test
    fun standbyToBoost_holds15sGraceFor6s_thenDropsTo2500ms() {
        var now = 10_000L
        val policy = CadenceTimeoutPolicy(monotonicClock = { now })

        // Starts in STANDBY
        assertEquals(MeshConfig.STANDBY_NEIGHBOR_TIMEOUT_MS, policy.directTimeoutMs())

        // Tighten at t=14_000 -> 6s grace until t=20_000
        now = 14_000L
        policy.onCadenceChanged(MeshCadence.BOOST)

        now = 14_500L
        assertEquals(15_000L, policy.directTimeoutMs())

        now = 19_999L
        assertEquals(15_000L, policy.directTimeoutMs())

        // Once grace expires at t=20_000, tightens to 2_500ms
        now = 20_000L
        assertEquals(MeshConfig.BOOST_NEIGHBOR_TIMEOUT_MS, policy.directTimeoutMs())
        assertEquals(MeshConfig.BOOST_REMOTE_REPORT_TIMEOUT_MS, policy.remoteReportTimeoutMs())
    }

    @Test
    fun boostToStandby_relaxesImmediatelyWithoutGrace() {
        var now = 10_000L
        val policy = CadenceTimeoutPolicy(monotonicClock = { now })
        policy.onCadenceChanged(MeshCadence.BOOST)

        now = 17_000L
        assertEquals(MeshConfig.BOOST_NEIGHBOR_TIMEOUT_MS, policy.directTimeoutMs())

        policy.onCadenceChanged(MeshCadence.STANDBY)
        assertEquals(MeshConfig.STANDBY_NEIGHBOR_TIMEOUT_MS, policy.directTimeoutMs())
        assertEquals(MeshConfig.STANDBY_REMOTE_REPORT_TIMEOUT_MS, policy.remoteReportTimeoutMs())
    }

    @Test
    fun standby_remoteReportTimeoutIs18s() {
        val policy = CadenceTimeoutPolicy(monotonicClock = { 1_000L })
        assertEquals(18_000L, policy.remoteReportTimeoutMs())
        policy.onCadenceChanged(MeshCadence.ACTIVE)
        assertEquals(18_000L, policy.remoteReportTimeoutMs())
    }
}

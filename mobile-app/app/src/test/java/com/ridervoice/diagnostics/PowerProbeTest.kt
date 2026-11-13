package com.ridervoice.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PowerProbeTest {

    private var virtualTimeMs = 1_000_000L

    @Before
    fun setUp() {
        PowerProbe.reset()
        virtualTimeMs = 1_000_000L
        PowerProbe.clockSupplier = { virtualTimeMs }
    }

    @After
    fun tearDown() {
        PowerProbe.reset()
    }

    @Test
    fun `wake lock duration tracks active and released time accurately`() {
        assertFalse(PowerProbe.snapshot().isWakeLockHeld)
        assertEquals(0L, PowerProbe.snapshot().totalWakeLockHeldMs)

        PowerProbe.recordWakeLockAcquired("TestLock")
        assertTrue(PowerProbe.snapshot().isWakeLockHeld)

        virtualTimeMs += 10_000L
        val midSnapshot = PowerProbe.snapshot()
        assertTrue(midSnapshot.isWakeLockHeld)
        assertEquals(10_000L, midSnapshot.totalWakeLockHeldMs)
        assertEquals(10_000L, midSnapshot.activeWakeLockDurationMs)

        virtualTimeMs += 5_000L
        PowerProbe.recordWakeLockReleased("TestLock")

        val releasedSnapshot = PowerProbe.snapshot()
        assertFalse(releasedSnapshot.isWakeLockHeld)
        assertEquals(15_000L, releasedSnapshot.totalWakeLockHeldMs)
        assertEquals(0L, releasedSnapshot.activeWakeLockDurationMs)
    }

    @Test
    fun `sliding window counts GPS, mesh, and poll rates within 60 seconds`() {
        // Record 5 GPS fixes, 10 mesh packets, 3 polls at t=0
        repeat(5) { PowerProbe.recordGpsFix() }
        repeat(10) { PowerProbe.recordMeshPacket() }
        repeat(3) { PowerProbe.recordPoll() }

        val snapshot = PowerProbe.snapshot()
        assertEquals(5, snapshot.gpsFixesPerMinute)
        assertEquals(10, snapshot.meshPacketsPerMinute)
        assertEquals(3, snapshot.pollsPerMinute)

        // Advance clock by 30 seconds (still within window)
        virtualTimeMs += 30_000L
        repeat(2) { PowerProbe.recordGpsFix() }

        val midSnapshot = PowerProbe.snapshot()
        assertEquals(7, midSnapshot.gpsFixesPerMinute)
        assertEquals(10, midSnapshot.meshPacketsPerMinute)
        assertEquals(3, midSnapshot.pollsPerMinute)

        // Advance clock past 60s total from the start (initial events expire)
        virtualTimeMs += 35_000L
        val agedSnapshot = PowerProbe.snapshot()
        assertEquals(2, agedSnapshot.gpsFixesPerMinute) // only the 2 fixes from 35s ago remain
        assertEquals(0, agedSnapshot.meshPacketsPerMinute) // all expired
        assertEquals(0, agedSnapshot.pollsPerMinute) // all expired
    }

    @Test
    fun `runtime test T8 simulates resource rates for lobby, online, and mesh modes`() {
        // --- 1. Lobby Mode Simulation ---
        // Expect: 0 GPS fixes, 0 mesh packets, ~20 polls/min (3s cadence), no wake lock held
        repeat(20) {
            PowerProbe.recordPoll()
            virtualTimeMs += 3_000L
        }
        val lobbySnapshot = PowerProbe.snapshot()
        assertFalse(lobbySnapshot.isWakeLockHeld)
        assertEquals(0, lobbySnapshot.gpsFixesPerMinute)
        assertEquals(0, lobbySnapshot.meshPacketsPerMinute)
        assertTrue("Lobby polls per min must be bounded <= 25", lobbySnapshot.pollsPerMinute in 18..22)

        PowerProbe.reset()
        virtualTimeMs = 2_000_000L
        PowerProbe.clockSupplier = { virtualTimeMs }

        // --- 2. Online Ride with Mesh Standby Simulation ---
        // Expect: Wake lock held, GPS ~12 fixes/min (5s adaptive rate), standby hello ~12 pkts/min (5s cadence), 0 polls
        PowerProbe.recordWakeLockAcquired("VoiceFGS")
        repeat(12) {
            PowerProbe.recordGpsFix()
            PowerProbe.recordMeshPacket()
            virtualTimeMs += 5_000L
        }
        val onlineSnapshot = PowerProbe.snapshot()
        assertTrue(onlineSnapshot.isWakeLockHeld)
        assertEquals(60_000L, onlineSnapshot.totalWakeLockHeldMs)
        assertEquals(12, onlineSnapshot.gpsFixesPerMinute)
        assertEquals(12, onlineSnapshot.meshPacketsPerMinute)
        assertEquals(0, onlineSnapshot.pollsPerMinute)

        PowerProbe.reset()
        virtualTimeMs = 3_000_000L
        PowerProbe.clockSupplier = { virtualTimeMs }

        // --- 3. Offline Mesh Active Ride Simulation ---
        // Expect: Wake lock held, GPS active, full mesh voice & hello active (<= 120 pkts/min budget)
        PowerProbe.recordWakeLockAcquired("MeshFGS")
        repeat(60) {
            PowerProbe.recordMeshPacket()
            virtualTimeMs += 1_000L
        }
        val meshSnapshot = PowerProbe.snapshot()
        assertTrue(meshSnapshot.isWakeLockHeld)
        assertTrue("Mesh packet rate must remain within forwarding budget", meshSnapshot.meshPacketsPerMinute <= 120)
    }
}

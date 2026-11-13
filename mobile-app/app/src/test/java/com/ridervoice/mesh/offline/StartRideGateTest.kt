package com.ridervoice.mesh.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRideGateTest {

    @Test
    fun `gate is closed when host is configuring and other riders are waiting`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.CHECKING)
        val riders = listOf(
            LobbyRiderStatus(riderId = "r1", status = "CONFIGURING", lastActivityMs = 1000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 2000L
        )

        assertFalse("Gate should be closed", result.isOpen)
        assertEquals(2, result.blockingRidersCount)
        assertEquals("WAITING_FOR_RIDERS", result.reason)
    }

    @Test
    fun `gate is open when all riders and host are in terminal states D1`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.READY)
        val riders = listOf(
            LobbyRiderStatus(riderId = "r1", status = "READY", lastActivityMs = 1000L),
            LobbyRiderStatus(riderId = "r2", status = "OPTED_OUT", lastActivityMs = 1000L),
            LobbyRiderStatus(riderId = "r3", status = "UNAVAILABLE_DEVICE", lastActivityMs = 1000L),
            LobbyRiderStatus(riderId = "r4", status = "FAILED", lastActivityMs = 1000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 2000L
        )

        assertTrue("Gate should be open when all are terminal", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals(5, result.readyRidersCount)
        assertEquals(5, result.totalRidersCount)
        assertEquals("ALL_RIDERS_READY", result.reason)
    }

    @Test
    fun `inactive rider past 90s is treated as timed out A2`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.READY)
        val riders = listOf(
            // r1 has been inactive for 95 seconds
            LobbyRiderStatus(riderId = "r1", status = "CONFIGURING", lastActivityMs = 5_000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 105_000L // 100s later (> 90s)
        )

        assertTrue("Gate should be open because inactive rider timed out per A2", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals(2, result.readyRidersCount)
    }

    @Test
    fun `vanished rider past 30s is treated as non-blocking A2`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.READY)
        val riders = listOf(
            // r1 isPresent=false or lastPresenceMs > 30s
            LobbyRiderStatus(riderId = "r1", status = "CONFIGURING", lastActivityMs = 50_000L, lastPresenceMs = 10_000L, isPresent = true)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 45_000L // 35s after last presence
        )

        assertTrue("Gate should open because vanished rider is non-blocking per A2", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
    }

    @Test
    fun `roomOfflineMeshEnabled false unblocks gate immediately D8 and A3`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.CHECKING)
        val riders = listOf(
            LobbyRiderStatus(riderId = "r1", status = "CONFIGURING", lastActivityMs = 1000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = false,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 2000L
        )

        assertTrue("Gate should be open when host disabled offline mesh for the room", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals("OFFLINE_MESH_DISABLED_BY_HOST", result.reason)
    }

    @Test
    fun `kill switch disabled opens gate immediately D14`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.CHECKING)
        val riders = listOf(
            LobbyRiderStatus(riderId = "r1", status = "CONFIGURING", lastActivityMs = 1000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = false, // Kill switch pulled (PER_ROOM_OFFLINE_CONFIG_ENABLED = false)
            serverNowMs = 2000L
        )

        assertTrue("Gate should be open when kill switch is active", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals("KILL_SWITCH_ACTIVE", result.reason)
    }

    @Test
    fun `solo ride allowed when rider list is empty`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.CHECKING)
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = emptyList(),
            allowSolo = true,
            featureEnabled = true,
            serverNowMs = 2000L
        )

        assertTrue("Gate should be open for solo ride", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals(1, result.readyRidersCount)
        assertEquals("SOLO_ALLOWED", result.reason)
    }

    @Test
    fun `host opt-out does not block ride`() {
        val hostState = OfflineConfigState(status = OfflineConfigStatus.OPTED_OUT, optOut = true)
        val riders = listOf(
            LobbyRiderStatus(riderId = "r1", status = "READY", lastActivityMs = 1000L)
        )
        val result = StartRideGate.evaluate(
            hostState = hostState,
            roomOfflineMeshEnabled = true,
            acceptedRiders = riders,
            allowSolo = false,
            featureEnabled = true,
            serverNowMs = 2000L
        )

        assertTrue("Gate should be open when host opts out and all other riders ready", result.isOpen)
        assertEquals(0, result.blockingRidersCount)
        assertEquals(2, result.readyRidersCount)
    }
}


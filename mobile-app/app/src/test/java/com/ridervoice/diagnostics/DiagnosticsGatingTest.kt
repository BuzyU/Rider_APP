package com.ridervoice.diagnostics

import com.ridervoice.models.RideSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiagnosticsGatingTest {

    @Before
    fun setUp() {
        RideSession.clear()
    }

    @Test
    fun `RideSession clear resets diagnosticsEnabled and testNumber`() {
        RideSession.diagnosticsEnabled = true
        RideSession.testNumber = 77
        RideSession.activeRoomName = "convoy-alpha"
        RideSession.livekitToken = "token"
        RideSession.livekitUrl = "url"
        RideSession.isHost = true

        RideSession.clear()

        assertFalse("RideSession.clear() must reset diagnosticsEnabled to false", RideSession.diagnosticsEnabled)
        assertNull("RideSession.clear() must reset testNumber to null", RideSession.testNumber)
        assertNull("RideSession.clear() must reset activeRoomName to null", RideSession.activeRoomName)
        assertEquals("", RideSession.livekitToken)
        assertEquals("", RideSession.livekitUrl)
        assertFalse(RideSession.isHost)
    }

    @Test
    fun `diagnostics gating starts collector only when connected AND flag on AND rider not opted out`() {
        // Condition: Connected + Flag ON + Not opted out -> true
        assertTrue(
            DiagnosticsGating.shouldStartCollector(
                isConnected = true,
                diagnosticsEnabled = true,
                isRiderOptedOut = false
            )
        )

        // Rider opted out -> false even if connected and flag on
        assertFalse(
            DiagnosticsGating.shouldStartCollector(
                isConnected = true,
                diagnosticsEnabled = true,
                isRiderOptedOut = true
            )
        )

        // Host disabled flag -> false even if connected
        assertFalse(
            DiagnosticsGating.shouldStartCollector(
                isConnected = true,
                diagnosticsEnabled = false,
                isRiderOptedOut = false
            )
        )

        // Disconnected -> false even if flag on and not opted out
        assertFalse(
            DiagnosticsGating.shouldStartCollector(
                isConnected = false,
                diagnosticsEnabled = true,
                isRiderOptedOut = false
            )
        )

        // Disconnected, disabled, opted out -> false
        assertFalse(
            DiagnosticsGating.shouldStartCollector(
                isConnected = false,
                diagnosticsEnabled = false,
                isRiderOptedOut = true
            )
        )
    }
}

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

    @Test
    fun `host switch default false end to end in models and RideSession`() {
        val createReq = com.ridervoice.models.ConvoyCreateRequest(convoyName = "test-convoy")
        assertFalse("ConvoyCreateRequest default diagnosticsEnabled must be false", createReq.diagnosticsEnabled)

        val createResp = com.ridervoice.models.ConvoyCreateResponse(roomId = "r1", convoyName = "c1", hostId = "h1")
        assertFalse("ConvoyCreateResponse default diagnosticsEnabled must be false", createResp.diagnosticsEnabled)

        val startResp = com.ridervoice.models.StartRideResponse(token = "tok", roomName = "r1", livekitUrl = "url")
        assertFalse("StartRideResponse default diagnosticsEnabled must be false", startResp.diagnosticsEnabled)

        val roomData = com.ridervoice.models.RoomData(roomName = "r1", token = "tok")
        assertFalse("RoomData default diagnosticsEnabled must be false", roomData.diagnosticsEnabled)

        assertFalse("RideSession default diagnosticsEnabled must be false", RideSession.diagnosticsEnabled)
    }

    @Test
    fun `a guest with default settings collects when host flag is on`() {
        // Default setting for "share diagnostics when a host enables it" is true
        val guestShareDiagnosticsWhenHostEnabledDefault = true
        val isOptedOut = !guestShareDiagnosticsWhenHostEnabledDefault

        assertFalse("Guest with default settings is NOT opted out", isOptedOut)

        val shouldCollect = DiagnosticsGating.shouldStartCollector(
            isConnected = true,
            diagnosticsEnabled = true, // Host turned diagnostics flag ON
            isRiderOptedOut = isOptedOut
        )

        assertTrue("Guest with default settings must collect when host flag is on", shouldCollect)
    }

    @Test
    fun `a guest who chose Don't share is opted out`() {
        // Guest chose "Don't share my data" -> shareDiagnosticsWhenHostEnabled set to false
        val guestShareDiagnosticsWhenHostEnabled = false
        val isOptedOut = !guestShareDiagnosticsWhenHostEnabled

        assertTrue("Guest who chose Don't share must be marked opted out", isOptedOut)

        val shouldCollect = DiagnosticsGating.shouldStartCollector(
            isConnected = true,
            diagnosticsEnabled = true, // Host turned diagnostics flag ON
            isRiderOptedOut = isOptedOut
        )

        assertFalse("Guest who chose Don't share must NOT collect even if host flag is on", shouldCollect)
    }
}

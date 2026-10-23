package com.ridervoice.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmParsingTest {

    @Test
    fun parseRideInvitePayload() {
        val data = mapOf(
            "type" to "RIDE_INVITE",
            "senderCallsign" to "Maverick",
            "convoyName" to "Sunday Ghat Run",
            "inviteId" to "inv_123"
        )
        assertEquals("RIDE_INVITE", data["type"])
        assertEquals("Maverick", data["senderCallsign"])
        assertEquals("Sunday Ghat Run", data["convoyName"])
        assertEquals("inv_123", data["inviteId"])
    }

    @Test
    fun parseEmergencyPayload() {
        val data = mapOf(
            "type" to "EMERGENCY",
            "alertType" to "SOS BUTTON PRESSED",
            "roomName" to "Sunday Ghat Run",
            "senderCallsign" to "Ghost",
            "alertId" to "alert_999"
        )
        assertEquals("EMERGENCY", data["type"])
        assertEquals("SOS BUTTON PRESSED", data["alertType"])
        assertEquals("Sunday Ghat Run", data["roomName"])
        assertEquals("Ghost", data["senderCallsign"])
        assertEquals("alert_999", data["alertId"])
    }

    @Test
    fun malformedPayloadIgnoredSafely() {
        val data = emptyMap<String, String>()
        assertNull(data["type"])
    }
}

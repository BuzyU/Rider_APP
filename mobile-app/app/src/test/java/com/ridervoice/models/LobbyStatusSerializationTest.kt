package com.ridervoice.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LobbyStatusSerializationTest {

    private val gson = Gson()

    @Test
    fun `deserializes legacy lobby status payload and preserves isHost and isRideStarted`() {
        val legacyJson = """
            {
                "roomId": "room-uuid-123",
                "convoyName": "Weekend-Ride",
                "isHost": true,
                "isRideStarted": false,
                "acceptedCount": 3,
                "pendingCount": 1,
                "declinedCount": 0,
                "canStart": true,
                "invites": [
                    {
                        "inviteId": "inv-1",
                        "status": "ACCEPTED",
                        "invitee": {
                            "id": "user-1",
                            "handle": "rider_one",
                            "displayName": "Rider One",
                            "bikeModel": "Duke 390"
                        }
                    }
                ]
            }
        """.trimIndent()

        val status = gson.fromJson(legacyJson, LobbyStatus::class.java)

        assertEquals("room-uuid-123", status.roomId)
        assertEquals("Weekend-Ride", status.convoyName)
        assertTrue(status.isHost)
        assertFalse(status.isRideStarted)
        assertEquals(3, status.acceptedCount)
        assertEquals(1, status.pendingCount)
        assertEquals(0, status.declinedCount)
        assertTrue(status.canStart)
        assertEquals(1, status.invites.size)
        assertEquals("Rider One", status.invites[0].invitee.displayName)
    }

    @Test
    fun `deserializes v3 lobby status payload with new fields and maintains legacy compatibility`() {
        val v3Json = """
            {
                "roomId": "room-uuid-456",
                "convoyName": "Ghat-Run",
                "status": "ACTIVE",
                "isHost": false,
                "isOwner": false,
                "isRideStarted": true,
                "startedAt": "2026-10-23T05:00:00.000Z",
                "diagnosticsEnabled": true,
                "origin": "Pune",
                "destination": "Lonavala",
                "meetupPoint": "Chandani Chowk",
                "estimatedDurationMin": 90,
                "host": {
                    "id": "host-user-99",
                    "callsign": "Maverick",
                    "handle": "mav_lead",
                    "displayName": "Maverick Lead",
                    "bikeModel": "Interceptor 650"
                },
                "invites": [
                    {
                        "inviteId": "inv-2",
                        "status": "ACCEPTED",
                        "source": "LINK",
                        "invitee": {
                            "id": "user-2",
                            "handle": "goose",
                            "displayName": "Goose Wingman",
                            "callsign": "Goose",
                            "bikeModel": "GT 650"
                        }
                    }
                ],
                "acceptedCount": 2,
                "pendingCount": 0,
                "declinedCount": 0,
                "canStart": false,
                "joinCode": "K7M2XQ9P"
            }
        """.trimIndent()

        val status = gson.fromJson(v3Json, LobbyStatus::class.java)

        assertEquals("room-uuid-456", status.roomId)
        assertEquals("Ghat-Run", status.convoyName)
        assertEquals("ACTIVE", status.status)
        assertFalse(status.isHost)
        assertFalse(status.isOwner ?: true)
        assertTrue(status.isRideStarted)
        assertTrue(status.diagnosticsEnabled)
        assertEquals("Maverick", status.host?.callsign)
        assertEquals("K7M2XQ9P", status.joinCode)
        assertEquals(1, status.invites.size)
        assertEquals("Goose", status.invites[0].invitee.callsign)
    }
}

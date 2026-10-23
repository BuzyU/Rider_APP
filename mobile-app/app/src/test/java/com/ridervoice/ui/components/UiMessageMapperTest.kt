package com.ridervoice.ui.components

import org.junit.Assert.*
import org.junit.Test

class UiMessageMapperTest {

    @Test
    fun mapsRoomEnded() {
        val msg = UiMessageMapper.map(410, "ROOM_ENDED")
        assertEquals("Ride ended", msg.title)
        assertEquals("This ride has ended.", msg.message)
        assertTrue(msg.isFatal)
        assertEquals("Go home", msg.actionText)
    }

    @Test
    fun mapsRemovedByHost() {
        val msg = UiMessageMapper.map(403, "REMOVED_BY_HOST")
        assertEquals("Removed from ride", msg.title)
        assertEquals("The host removed you from this ride.", msg.message)
        assertTrue(msg.isFatal)
    }

    @Test
    fun mapsNotAMember() {
        val msg = UiMessageMapper.map(403, "NOT_A_MEMBER")
        assertEquals("Not a member", msg.title)
        assertEquals("You're not part of this ride. Ask the host for a code.", msg.message)
    }

    @Test
    fun mapsLinkExpired() {
        val msg = UiMessageMapper.map(410, "LINK_EXPIRED")
        assertEquals("Link expired", msg.title)
        assertEquals("This invite link has expired. Ask for a new one.", msg.message)
    }

    @Test
    fun mapsInvalidCode() {
        val msg = UiMessageMapper.map(404, "CODE_NOT_FOUND")
        assertEquals("Invalid code", msg.title)
        assertEquals("That code isn't right. Check it and try again.", msg.message)
    }

    @Test
    fun mapsAlreadyMember() {
        val msg = UiMessageMapper.map(409, "ALREADY_MEMBER")
        assertEquals("Already joined", msg.title)
        assertEquals("You're already in this ride.", msg.message)
    }

    @Test
    fun mapsUpgradeRequired() {
        val msg = UiMessageMapper.map(426, "UPGRADE_REQUIRED")
        assertEquals("Update required", msg.title)
        assertEquals("Please update RiderVoice to continue.", msg.message)
        assertTrue(msg.isFatal)
    }

    @Test
    fun mapsRateLimited() {
        val msg = UiMessageMapper.map(429, "RATE_LIMITED")
        assertEquals("Too many attempts", msg.title)
        assertEquals("Too many tries. Wait a minute.", msg.message)
    }

    @Test
    fun mapsServerError() {
        val msg = UiMessageMapper.map(503)
        assertEquals("Connection issue", msg.title)
        assertEquals("Can't reach RiderVoice. We'll keep trying.", msg.message)
    }

    @Test
    fun mapsUnknownFallback() {
        val msg = UiMessageMapper.map(400, fallback = "Custom reason")
        assertEquals("Notice", msg.title)
        assertEquals("Custom reason", msg.message)
    }
}

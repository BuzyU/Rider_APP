package com.ridervoice.utils

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class JwtRoomClaimDecoderTest {

    // Payload: {"video":{"room":"convoy-alpha","roomJoin":true},"sub":"user123"}
    private val validAlphaPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"video":{"room":"convoy-alpha","roomJoin":true},"sub":"user123"}""".toByteArray(StandardCharsets.UTF_8)
    )
    private val validAlphaToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.$validAlphaPayload.signature123"

    // Payload without video or room claim: {"sub":"user123","iss":"APIkey"}
    private val missingClaimPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"sub":"user123","iss":"APIkey"}""".toByteArray(StandardCharsets.UTF_8)
    )
    private val missingClaimToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.$missingClaimPayload.signature123"

    @Test
    fun `extractRoomName returns expected room for valid token`() {
        val room = JwtRoomClaimDecoder.extractRoomName(validAlphaToken)
        assertEquals("convoy-alpha", room)
    }

    @Test
    fun `validateRoom returns true when room matches expected`() {
        assertTrue(JwtRoomClaimDecoder.validateRoom(validAlphaToken, "convoy-alpha"))
    }

    @Test
    fun `validateRoom returns false when room is wrong`() {
        assertFalse(JwtRoomClaimDecoder.validateRoom(validAlphaToken, "convoy-beta"))
        assertFalse(JwtRoomClaimDecoder.validateRoom(validAlphaToken, "CONVOY-ALPHA"))
    }

    @Test
    fun `extractRoomName returns null for malformed token`() {
        assertNull(JwtRoomClaimDecoder.extractRoomName(null))
        assertNull(JwtRoomClaimDecoder.extractRoomName(""))
        assertNull(JwtRoomClaimDecoder.extractRoomName("not-a-jwt"))
        assertNull(JwtRoomClaimDecoder.extractRoomName("header.!!!invalidbase64!!!.sig"))
    }

    @Test
    fun `validateRoom returns false for malformed token`() {
        assertFalse(JwtRoomClaimDecoder.validateRoom("invalid.token", "convoy-alpha"))
        assertFalse(JwtRoomClaimDecoder.validateRoom(null, "convoy-alpha"))
        assertFalse(JwtRoomClaimDecoder.validateRoom("", "convoy-alpha"))
    }

    @Test
    fun `validateRoom returns false when video room claim is missing`() {
        assertNull(JwtRoomClaimDecoder.extractRoomName(missingClaimToken))
        assertFalse(JwtRoomClaimDecoder.validateRoom(missingClaimToken, "convoy-alpha"))
    }
}

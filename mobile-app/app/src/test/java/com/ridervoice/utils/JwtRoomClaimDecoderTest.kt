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

    private fun tokenWith(room: String, exp: Long?): String {
        val expPart = if (exp != null) ""","exp":$exp""" else ""
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"video":{"room":"$room","roomJoin":true}$expPart}""".toByteArray(StandardCharsets.UTF_8)
        )
        return "h.$payload.s"
    }

    @Test
    fun `validate accepts fresh token for matching room`() {
        val t = tokenWith("room-1", 10_000)
        assertEquals(JwtRoomClaimDecoder.TokenCheck.VALID, JwtRoomClaimDecoder.validate(t, "room-1", nowEpochSeconds = 1_000))
    }

    @Test
    fun `validate rejects expired token`() {
        val t = tokenWith("room-1", 1_000)
        assertEquals(JwtRoomClaimDecoder.TokenCheck.EXPIRED, JwtRoomClaimDecoder.validate(t, "room-1", nowEpochSeconds = 1_100))
    }

    @Test
    fun `validate asks for refresh when near expiry`() {
        val t = tokenWith("room-1", 1_200)
        assertEquals(JwtRoomClaimDecoder.TokenCheck.NEEDS_REFRESH, JwtRoomClaimDecoder.validate(t, "room-1", nowEpochSeconds = 1_000))
    }

    @Test
    fun `validate rejects wrong room`() {
        val t = tokenWith("room-1", 10_000)
        assertEquals(JwtRoomClaimDecoder.TokenCheck.WRONG_ROOM, JwtRoomClaimDecoder.validate(t, "room-2", nowEpochSeconds = 1_000))
    }

    @Test
    fun `validate treats token without exp as valid and malformed as malformed`() {
        assertEquals(JwtRoomClaimDecoder.TokenCheck.VALID, JwtRoomClaimDecoder.validate(tokenWith("room-1", null), "room-1"))
        assertEquals(JwtRoomClaimDecoder.TokenCheck.MALFORMED, JwtRoomClaimDecoder.validate("garbage", "room-1"))
    }

    @Test
    fun `validate accepts token matching expectedRoomId in UUID mode`() {
        val t = tokenWith("d3b07384-d113-40f4-a0ff-4e943c7b398a", 10_000)
        assertEquals(
            JwtRoomClaimDecoder.TokenCheck.VALID,
            JwtRoomClaimDecoder.validate(
                token = t,
                expectedRoomId = "d3b07384-d113-40f4-a0ff-4e943c7b398a",
                expectedRoomName = "Sunday-Ride",
                nowEpochSeconds = 1_000
            )
        )
    }

    @Test
    fun `validate accepts token matching expectedRoomName in legacy mode`() {
        val t = tokenWith("Sunday-Ride", 10_000)
        assertEquals(
            JwtRoomClaimDecoder.TokenCheck.VALID,
            JwtRoomClaimDecoder.validate(
                token = t,
                expectedRoomId = "d3b07384-d113-40f4-a0ff-4e943c7b398a",
                expectedRoomName = "Sunday-Ride",
                nowEpochSeconds = 1_000
            )
        )
    }

    @Test
    fun `validate rejects token when both expectedRoomId and expectedRoomName mismatch`() {
        val t = tokenWith("Other-Ride-UUID", 10_000)
        assertEquals(
            JwtRoomClaimDecoder.TokenCheck.WRONG_ROOM,
            JwtRoomClaimDecoder.validate(
                token = t,
                expectedRoomId = "d3b07384-d113-40f4-a0ff-4e943c7b398a",
                expectedRoomName = "Sunday-Ride",
                nowEpochSeconds = 1_000
            )
        )
    }

    @Test
    fun `validate strictly requires expectedLkRoomName when provided`() {
        // Token has legacy display name "Sunday-Ride", but expectedLkRoomName is "rv_d3b07384"
        val t = tokenWith("Sunday-Ride", 10_000)
        assertEquals(
            JwtRoomClaimDecoder.TokenCheck.WRONG_ROOM,
            JwtRoomClaimDecoder.validate(
                token = t,
                expectedRoomId = "d3b07384-d113-40f4-a0ff-4e943c7b398a",
                expectedRoomName = "Sunday-Ride",
                nowEpochSeconds = 1_000,
                expectedLkRoomName = "rv_d3b07384-d113-40f4-a0ff-4e943c7b398a"
            )
        )
    }

    @Test
    fun `validate accepts token matching expectedLkRoomName`() {
        val canonical = "rv_d3b07384-d113-40f4-a0ff-4e943c7b398a"
        val t = tokenWith(canonical, 10_000)
        assertEquals(
            JwtRoomClaimDecoder.TokenCheck.VALID,
            JwtRoomClaimDecoder.validate(
                token = t,
                expectedRoomId = "d3b07384-d113-40f4-a0ff-4e943c7b398a",
                expectedRoomName = "Sunday-Ride",
                nowEpochSeconds = 1_000,
                expectedLkRoomName = canonical
            )
        )
    }
}
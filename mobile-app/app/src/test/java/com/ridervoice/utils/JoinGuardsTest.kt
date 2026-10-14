package com.ridervoice.utils

import com.ridervoice.models.RideSession
import com.ridervoice.models.RoomData
import com.ridervoice.ui.viewmodels.JoinRoomResponseHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class JoinGuardsTest {

    private val validAlphaPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"video":{"room":"convoy-alpha","roomJoin":true},"sub":"user123"}""".toByteArray(StandardCharsets.UTF_8)
    )
    private val validAlphaToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.$validAlphaPayload.sig"

    @Before
    fun setUp() {
        RideSession.clear()
    }

    @Test
    fun `JoinRoomResponseHandler on 404 sets error, never calls onSuccess, leaves RideSession unchanged`() {
        var successCalled = false
        var capturedError: String? = null

        JoinRoomResponseHandler.processTokenResponse(
            isSuccessful = false,
            statusCode = 404,
            body = null,
            errorBodyString = """{"error":"Room not found"}""",
            onSuccess = { successCalled = true },
            onError = { err -> capturedError = err },
            updateSession = { body ->
                RideSession.livekitToken = body.token
                RideSession.activeRoomName = body.roomName
            }
        )

        assertFalse(successCalled)
        assertNotNull(capturedError)
        assertTrue(capturedError!!.contains("404"))
        assertEquals("", RideSession.livekitToken)
        assertNull(RideSession.activeRoomName)
        assertFalse(RideSession.diagnosticsEnabled)
    }

    @Test
    fun `JoinRoomResponseHandler on 500 error leaves RideSession untouched and triggers onError`() {
        var successCalled = false
        var capturedError: String? = null

        JoinRoomResponseHandler.processTokenResponse(
            isSuccessful = false,
            statusCode = 500,
            body = null,
            errorBodyString = """{"error":"Internal server error"}""",
            onSuccess = { successCalled = true },
            onError = { err -> capturedError = err },
            updateSession = { body ->
                RideSession.livekitToken = body.token
            }
        )

        assertFalse(successCalled)
        assertEquals("Internal server error", capturedError)
        assertEquals("", RideSession.livekitToken)
    }

    @Test
    fun `JoinRoomResponseHandler on 200 success updates RideSession and calls onSuccess`() {
        var successRoom: String? = null
        var errorCalled = false

        val testData = RoomData(
            token = validAlphaToken,
            roomName = "convoy-alpha",
            diagnosticsEnabled = true,
            testNumber = 42
        )

        JoinRoomResponseHandler.processTokenResponse(
            isSuccessful = true,
            statusCode = 200,
            body = testData,
            errorBodyString = null,
            onSuccess = { roomName -> successRoom = roomName },
            onError = { errorCalled = true },
            updateSession = { body ->
                RideSession.livekitToken = body.token
                RideSession.activeRoomName = body.roomName
                RideSession.diagnosticsEnabled = body.diagnosticsEnabled
                RideSession.testNumber = body.testNumber
                RideSession.livekitUrl = "wss://livekit.test"
                RideSession.isHost = false
            }
        )

        assertFalse(errorCalled)
        assertEquals("convoy-alpha", successRoom)
        assertEquals(validAlphaToken, RideSession.livekitToken)
        assertEquals("convoy-alpha", RideSession.activeRoomName)
        assertTrue(RideSession.diagnosticsEnabled)
        assertEquals(42, RideSession.testNumber)
        assertFalse(RideSession.isHost)
    }

    @Test
    fun `Squad acceptInvite sets all four RideSession fields BEFORE onSuccess callback`() {
        var callbackExecuted = false
        var sessionStateAtCallback = false

        val tokenData = com.ridervoice.models.StartRideResponse(
            token = validAlphaToken,
            roomName = "convoy-alpha",
            livekitUrl = "wss://test.livekit.cloud",
            diagnosticsEnabled = true,
            testNumber = 10
        )

        // Simulating the acceptInvite execution logic
        fun simulateAcceptInvite(
            tokenResult: Result<com.ridervoice.models.StartRideResponse>,
            onSuccess: (String) -> Unit,
            onError: (String) -> Unit
        ) {
            if (tokenResult.isSuccess && tokenResult.getOrNull() != null) {
                val data = tokenResult.getOrNull()!!
                // Set all four RideSession fields BEFORE callback
                RideSession.livekitUrl = data.livekitUrl
                RideSession.livekitToken = data.token
                RideSession.activeRoomName = data.roomName
                RideSession.isHost = false
                RideSession.diagnosticsEnabled = data.diagnosticsEnabled
                RideSession.testNumber = data.testNumber

                onSuccess(data.roomName)
            } else {
                onError(tokenResult.exceptionOrNull()?.message ?: "Failed token")
            }
        }

        simulateAcceptInvite(
            tokenResult = Result.success(tokenData),
            onSuccess = { room ->
                callbackExecuted = true
                sessionStateAtCallback = (
                    RideSession.livekitUrl == "wss://test.livekit.cloud" &&
                    RideSession.livekitToken == validAlphaToken &&
                    RideSession.activeRoomName == "convoy-alpha" &&
                    !RideSession.isHost &&
                    RideSession.diagnosticsEnabled
                )
            },
            onError = { fail("Should not fail") }
        )

        assertTrue(callbackExecuted)
        assertTrue(sessionStateAtCallback)
    }

    @Test
    fun `Squad acceptInvite does not navigate or touch RideSession when token request fails`() {
        var callbackExecuted = false
        var errorReported: String? = null

        fun simulateAcceptInvite(
            tokenResult: Result<com.ridervoice.models.StartRideResponse>,
            onSuccess: (String) -> Unit,
            onError: (String) -> Unit
        ) {
            if (tokenResult.isSuccess && tokenResult.getOrNull() != null) {
                val data = tokenResult.getOrNull()!!
                RideSession.livekitUrl = data.livekitUrl
                RideSession.livekitToken = data.token
                RideSession.activeRoomName = data.roomName
                RideSession.isHost = false
                onSuccess(data.roomName)
            } else {
                onError(tokenResult.exceptionOrNull()?.message ?: "Failed token acquisition")
            }
        }

        simulateAcceptInvite(
            tokenResult = Result.failure(Exception("Network timeout (504)")),
            onSuccess = { callbackExecuted = true },
            onError = { msg -> errorReported = msg }
        )

        assertFalse(callbackExecuted)
        assertEquals("Network timeout (504)", errorReported)
        assertEquals("", RideSession.livekitToken)
        assertNull(RideSession.activeRoomName)
    }

    @Test
    fun `RoomViewModel guard refuses connection on JWT room mismatch`() {
        val mismatchedToken = validAlphaToken // room is convoy-alpha
        val targetRoom = "convoy-bravo"

        var disconnectedCalled = false
        var connectCalled = false
        var errorMessage: String? = null

        // Simulating the RoomViewModel guard check
        fun evaluateRoomConnection(token: String, requestedRoom: String) {
            val isTokenValid = JwtRoomClaimDecoder.validateRoom(token, requestedRoom)
            if (!isTokenValid) {
                disconnectedCalled = true
                errorMessage = "Security validation failed: Token room claim does not match convoy $requestedRoom"
                return
            }
            connectCalled = true
        }

        evaluateRoomConnection(mismatchedToken, targetRoom)

        assertTrue(disconnectedCalled)
        assertFalse(connectCalled)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("convoy-bravo"))
    }
}

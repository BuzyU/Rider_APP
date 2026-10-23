package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.LobbyJoinRequest
import com.ridervoice.models.TokenPayload
import com.ridervoice.network.ApiService
import com.ridervoice.state.ActiveRideRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JoinRoomViewModel @Inject constructor(
    private val apiService: ApiService,
    private val activeRideRepository: ActiveRideRepository
) : ViewModel() {

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun extractTokenOrCode(input: String): String = JoinRoomResponseHandler.extractTokenOrCode(input)

    fun smartJoin(input: String, userName: String, onSuccess: (roomName: String) -> Unit) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            _error.value = "Please enter a ride code or paste an invite link"
            return
        }

        val isLink = trimmed.contains("://") || trimmed.contains("/j/") || trimmed.contains("/join/") || trimmed.contains("token=")
        val clean = extractTokenOrCode(trimmed)

        val kind = when {
            isLink || clean.matches(Regex("^[a-fA-F0-9]{16}$")) -> "TOKEN"
            clean.matches(Regex("^[A-Za-z0-9]{8}$")) -> "CODE"
            else -> {
                _error.value = "That doesn't look like a ride code. Codes are 8 characters."
                return
            }
        }

        viewModelScope.launch {
            _isConnecting.value = true
            _error.value = null
            try {
                val res = apiService.joinLobbyUnified(LobbyJoinRequest(kind = kind, value = clean))
                if (res.isSuccessful && res.body() != null) {
                    val body = res.body()!!
                    activeRideRepository.enterLobby(
                        roomId = body.roomId ?: body.roomName,
                        name = body.roomName,
                        isHost = false
                    )
                    activeRideRepository.applyTokenPayload(
                        TokenPayload(
                            token = body.token,
                            livekitUrl = body.livekitUrl,
                            roomId = body.roomId,
                            roomName = body.roomName,
                            diagnosticsEnabled = body.diagnosticsEnabled,
                            testNumber = body.testNumber
                        )
                    )
                    onSuccess(body.roomName)
                } else {
                    val msg = when (res.code()) {
                        404 -> if (kind == "CODE") "Ride code not found. Check the code and try again." else "Invalid or expired invite link."
                        410 -> "This ride has already ended."
                        403 -> "You were removed from this ride and cannot rejoin."
                        409 -> "You are already a member of this ride."
                        else -> "Failed to join ride (code ${res.code()})."
                    }
                    _error.value = msg
                }
            } catch (e: Exception) {
                _error.value = "Connection error: ${e.localizedMessage ?: "Please check your network and retry."}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun joinViaToken(token: String, onSuccess: (roomName: String) -> Unit) {
        smartJoin(token, "", onSuccess)
    }

    fun joinByRoomCode(roomCode: String, userName: String, onSuccess: (roomName: String) -> Unit) {
        smartJoin(roomCode, userName, onSuccess)
    }

    fun clearError() {
        _error.value = null
    }
}

object JoinRoomResponseHandler {
    fun extractTokenOrCode(input: String): String {
        var cleaned = input.trim()
        if (cleaned.startsWith("ridervoice://join/", ignoreCase = true)) {
            cleaned = cleaned.substring("ridervoice://join/".length)
        } else if (cleaned.contains("/j/")) {
            cleaned = cleaned.substringAfter("/j/")
        } else if (cleaned.contains("/join/")) {
            cleaned = cleaned.substringAfter("/join/")
        }
        if (cleaned.contains("token=")) {
            cleaned = cleaned.substringAfter("token=")
        }
        cleaned = cleaned.substringBefore("?").substringBefore("&").substringBefore("#").trim()
        return cleaned.removeSuffix("/")
    }

    fun processTokenResponse(
        isSuccessful: Boolean,
        statusCode: Int,
        body: com.ridervoice.models.RoomData?,
        errorBodyString: String?,
        onSuccess: (roomName: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        updateSession: (com.ridervoice.models.RoomData) -> Unit
    ) {
        if (isSuccessful && body != null) {
            updateSession(body)
            onSuccess(body.roomName)
        } else {
            val errorMsg = if (statusCode == 404) {
                "Ride not found (404)"
            } else {
                errorBodyString?.let { b ->
                    try {
                        org.json.JSONObject(b).optString("error", "Error $statusCode")
                    } catch (e: Exception) { null }
                } ?: "Failed to connect to ride ($statusCode)"
            }
            onError(errorMsg)
        }
    }
}

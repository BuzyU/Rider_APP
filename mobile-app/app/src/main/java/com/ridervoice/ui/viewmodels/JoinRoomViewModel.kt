package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.JoinViaTokenRequest
import com.ridervoice.models.RideSession
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JoinRoomViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun joinViaToken(token: String, onSuccess: (roomName: String) -> Unit) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            _error.value = "Invalid or missing join token"
            return
        }
        viewModelScope.launch {
            _isConnecting.value = true
            _error.value = null
            try {
                val res = apiService.joinViaToken(JoinViaTokenRequest(cleanToken))
                if (res.isSuccessful && res.body() != null) {
                    val body = res.body()!!
                    RideSession.livekitToken = body.token
                    RideSession.livekitUrl = body.livekitUrl
                    RideSession.activeRoomName = body.roomName
                    RideSession.isHost = false
                    onSuccess(body.roomName)
                } else {
                    val msg = res.errorBody()?.string()?.let { b ->
                        try {
                            val obj = org.json.JSONObject(b)
                            if (obj.has("error")) obj.getString("error") else null
                        } catch (e: Exception) { null }
                    } ?: when (res.code()) {
                        404 -> "Invalid or expired join link"
                        410 -> "This convoy join link has expired"
                        403 -> "You are not authorized to join this convoy"
                        else -> "Failed to join convoy (${res.code()})"
                    }
                    _error.value = msg
                }
            } catch (e: Exception) {
                _error.value = "Connection failed: ${e.localizedMessage ?: "Check your network"}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun joinByRoomCode(roomCode: String, userName: String, onSuccess: (roomName: String) -> Unit) {
        val cleanCode = roomCode.trim()
        if (cleanCode.isBlank()) {
            _error.value = "Room callsign/code is required"
            return
        }
        viewModelScope.launch {
            _isConnecting.value = true
            _error.value = null
            try {
                val res = apiService.getRoomToken(com.ridervoice.models.RoomTokenRequest(cleanCode))
                JoinRoomResponseHandler.processTokenResponse(
                    isSuccessful = res.isSuccessful,
                    statusCode = res.code(),
                    body = res.body(),
                    errorBodyString = res.errorBody()?.string(),
                    onSuccess = { roomName ->
                        onSuccess(roomName)
                    },
                    onError = { msg ->
                        _error.value = msg
                    },
                    updateSession = { body ->
                        RideSession.livekitToken = body.token
                        RideSession.activeRoomName = body.roomName
                        RideSession.diagnosticsEnabled = body.diagnosticsEnabled
                        RideSession.testNumber = body.testNumber
                        RideSession.livekitUrl = com.ridervoice.utils.Constants.LIVEKIT_URL
                        RideSession.isHost = false
                    }
                )
            } catch (e: Exception) {
                _error.value = "Connection error: ${e.localizedMessage ?: "Check network"}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

object JoinRoomResponseHandler {
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
                "Convoy room not found (404)"
            } else {
                errorBodyString?.let { b ->
                    try {
                        org.json.JSONObject(b).optString("error", "Error $statusCode")
                    } catch (e: Exception) { null }
                } ?: "Failed to connect to convoy frequency ($statusCode)"
            }
            onError(errorMsg)
        }
    }
}

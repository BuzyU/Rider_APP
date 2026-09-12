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
                } else if (res.code() == 404) {

                    RideSession.activeRoomName = cleanToken
                    RideSession.isHost = false
                    onSuccess(cleanToken)
                } else {
                    val errorMsg = res.errorBody()?.string()?.let { body ->
                        try {
                            val json = org.json.JSONObject(body)
                            json.optString("error", "Error ${res.code()}")
                        } catch (e: Exception) {
                            null
                        }
                    } ?: "Link expired or invalid"
                    _error.value = errorMsg
                }
            } catch (e: Exception) {

                RideSession.activeRoomName = cleanToken
                RideSession.isHost = false
                onSuccess(cleanToken)
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.LobbyStatus
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _lobbyStatus = MutableStateFlow<LobbyStatus?>(null)
    val lobbyStatus: StateFlow<LobbyStatus?> = _lobbyStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var isPolling = false

    fun startPolling(roomName: String) {
        isPolling = true
        viewModelScope.launch {
            while (isPolling) {
                try {
                    val response = apiService.getLobbyStatus(roomName)
                    if (response.isSuccessful) {
                        _lobbyStatus.value = response.body()
                    }
                } catch (e: Exception) {
                    _error.value = e.message
                }
                delay(3000) // Poll every 3 seconds
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private val _shareLink = MutableStateFlow<String?>(null)
    val shareLink: StateFlow<String?> = _shareLink.asStateFlow()

    private val _isGeneratingLink = MutableStateFlow(false)
    val isGeneratingLink: StateFlow<Boolean> = _isGeneratingLink.asStateFlow()

    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

    fun generateShareLink(roomName: String) {
        val safeRoom = roomName.ifBlank { "Convoy" }
        viewModelScope.launch {
            _isGeneratingLink.value = true
            _linkError.value = null
            try {
                val res = apiService.generateShareLink(safeRoom)
                if (res.isSuccessful && res.body() != null) {
                    _shareLink.value = res.body()!!.shareUrl
                } else {
                    // Resilient fallback: direct convoy room URI ensures rider invites work immediately
                    _shareLink.value = "ridervoice://join/${android.net.Uri.encode(safeRoom)}"
                }
            } catch (e: Exception) {
                // Network or offline fallback
                _shareLink.value = "ridervoice://join/${android.net.Uri.encode(safeRoom)}"
            } finally {
                _isGeneratingLink.value = false
            }
        }
    }

    fun removeRider(roomName: String, userId: String) {
        viewModelScope.launch {
            try {
                val res = apiService.removeRiderFromConvoy(roomName, userId)
                if (res.isSuccessful) {
                    // Trigger immediate status refresh
                    val statusRes = apiService.getLobbyStatus(roomName)
                    if (statusRes.isSuccessful) {
                        _lobbyStatus.value = statusRes.body()
                    }
                } else {
                    _error.value = "Failed to remove rider"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to remove rider"
            }
        }
    }

    fun endRide(roomName: String, onEnded: () -> Unit) {
        viewModelScope.launch {
            try {
                apiService.endRideForEveryone(roomName)
                onEnded()
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to end ride"
            }
        }
    }

    fun transferHost(roomName: String, newHostId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.transferHost(roomName, com.ridervoice.models.TransferHostRequest(newHostId))
                if (res.isSuccessful) {
                    onSuccess()
                } else {
                    _error.value = "Failed to transfer convoy leadership"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to transfer leadership"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun startRide(roomName: String, onStartSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.startRide(roomName)
                if (res.isSuccessful && res.body() != null) {
                    com.ridervoice.models.RideSession.livekitToken = res.body()!!.token
                    com.ridervoice.models.RideSession.livekitUrl = res.body()!!.livekitUrl
                    com.ridervoice.models.RideSession.activeRoomName = roomName
                    com.ridervoice.models.RideSession.isHost = true
                    onStartSuccess()
                } else {
                    _error.value = "Failed to start ride: ${res.errorBody()?.string() ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
}

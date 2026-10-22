package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.LobbyStatus
import com.ridervoice.models.RideSession
import com.ridervoice.models.TransferHostRequest
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _lobbyStatus = MutableStateFlow<LobbyStatus?>(null)
    val lobbyStatus: StateFlow<LobbyStatus?> = _lobbyStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Why polling stopped for good, or null while the room is still live/pollable (H3). */
    enum class Terminal { ENDED, REMOVED, NOT_FOUND }

    private val _terminal = MutableStateFlow<Terminal?>(null)
    val terminal: StateFlow<Terminal?> = _terminal.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(roomName: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val response = apiService.getLobbyStatus(roomName)
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        _lobbyStatus.value = body
                        RideSession.isHost = body.isHost
                        RideSession.activeRoomName = body.convoyName
                        RideSession.activeRoomId = body.roomId
                        if (!body.isHost && RideSession.livekitToken.isNullOrBlank()) {
                            try {
                                val tokenRes = apiService.getJoinToken(com.ridervoice.models.JoinTokenRequest(roomName = body.convoyName, roomId = body.roomId))
                                if (tokenRes.isSuccessful && tokenRes.body() != null) {
                                    val tb = tokenRes.body()!!
                                    RideSession.livekitToken = tb.token
                                    RideSession.livekitUrl = tb.livekitUrl.ifBlank { com.ridervoice.utils.Constants.LIVEKIT_URL }
                                    RideSession.diagnosticsEnabled = tb.diagnosticsEnabled
                                    RideSession.testNumber = tb.testNumber
                                    RideSession.activeRoomId = tb.roomId ?: body.roomId
                                    RideSession.activeRoomName = tb.roomName
                                }
                            } catch (_: Exception) {}
                        }
                    } else when (response.code()) {
                        410 -> {
                            _terminal.value = Terminal.ENDED
                            _error.value = "This convoy has ended"
                            break
                        }
                        403 -> {
                            _terminal.value = Terminal.REMOVED
                            _error.value = "You are no longer part of this convoy"
                            break
                        }
                        404 -> {
                            _terminal.value = Terminal.NOT_FOUND
                            _error.value = "Convoy not found"
                            break
                        }
                        else -> { /* transient (5xx etc.): keep polling */ }
                    }
                } catch (e: Exception) {
                    _error.value = e.localizedMessage ?: "Network connection issue"
                }
                delay(3000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
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
                    _linkError.value = "Failed to generate link (${res.code()})"
                }
            } catch (e: Exception) {
                _linkError.value = e.localizedMessage ?: "Failed to generate link"
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
                val res = apiService.transferHost(roomName, TransferHostRequest(newHostId))
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
                    val body = res.body()!!
                    RideSession.livekitToken = body.token
                    RideSession.livekitUrl = body.livekitUrl
                    RideSession.activeRoomName = body.roomName
                    RideSession.activeRoomId = body.roomId
                    RideSession.isHost = true
                    onStartSuccess()
                } else {
                    _error.value = "Failed to start ride: ${res.errorBody()?.string() ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

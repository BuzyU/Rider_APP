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
    private val apiService: ApiService,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository
) : ViewModel() {

    private val _lobbyStatus = MutableStateFlow<LobbyStatus?>(null)
    val lobbyStatus: StateFlow<LobbyStatus?> = _lobbyStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _uiMessage = MutableStateFlow<com.ridervoice.ui.components.UiMessage?>(null)
    val uiMessage: StateFlow<com.ridervoice.ui.components.UiMessage?> = _uiMessage.asStateFlow()

    /** Why polling stopped for good, or null while the room is still live/pollable (H3). */
    enum class Terminal { ENDED, REMOVED, NOT_FOUND }

    private val _terminal = MutableStateFlow<Terminal?>(null)
    val terminal: StateFlow<Terminal?> = _terminal.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(roomName: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val response = apiService.getLobbyStatus(roomName)
                    if (response.isSuccessful && response.body() != null) {
                        consecutiveErrors = 0
                        val body = response.body()!!
                        _lobbyStatus.value = body

                        activeRideRepository.enterLobby(
                            roomId = body.roomId ?: roomName,
                            name = body.convoyName ?: roomName,
                            isHost = body.isHost ?: false,
                            joinCode = body.joinCode
                        )

                        if (body.isRideStarted == true) {
                            activeRideRepository.goLive()
                            if (activeRideRepository.state.value?.token.isNullOrBlank()) {
                                try {
                                    val tokenRes = apiService.getJoinToken(com.ridervoice.models.JoinTokenRequest(roomName = body.convoyName, roomId = body.roomId))
                                    if (tokenRes.isSuccessful && tokenRes.body() != null) {
                                        val tb = tokenRes.body()!!
                                        activeRideRepository.applyTokenPayload(
                                            com.ridervoice.models.TokenPayload(
                                                token = tb.token,
                                                livekitUrl = tb.livekitUrl,
                                                roomId = tb.roomId ?: body.roomId,
                                                roomName = tb.roomName ?: body.convoyName,
                                                diagnosticsEnabled = tb.diagnosticsEnabled,
                                                testNumber = tb.testNumber
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } else when (response.code()) {
                        410 -> {
                            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.ENDED_BY_HOST)
                            _terminal.value = Terminal.ENDED
                            val msg = com.ridervoice.ui.components.UiMessageMapper.map(410)
                            _uiMessage.value = msg
                            _error.value = msg.message
                            break
                        }
                        403 -> {
                            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.REMOVED)
                            _terminal.value = Terminal.REMOVED
                            val msg = com.ridervoice.ui.components.UiMessageMapper.map(403, "REMOVED_BY_HOST")
                            _uiMessage.value = msg
                            _error.value = msg.message
                            break
                        }
                        404 -> {
                            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.NOT_FOUND)
                            _terminal.value = Terminal.NOT_FOUND
                            val msg = com.ridervoice.ui.components.UiMessageMapper.map(404)
                            _uiMessage.value = msg
                            _error.value = msg.message
                            break
                        }
                        else -> {
                            consecutiveErrors++
                        }
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    val msg = com.ridervoice.ui.components.UiMessageMapper.map(500, fallback = e.localizedMessage ?: "Network connection issue")
                    _uiMessage.value = msg
                    _error.value = msg.message
                }
                val delayMs = if (consecutiveErrors >= 3) 10000L else 3000L
                delay(delayMs)
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
        val safeRoom = roomName.ifBlank { "Ride" }
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
                    _error.value = "Failed to transfer ride host"
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
                    activeRideRepository.applyTokenPayload(
                        com.ridervoice.models.TokenPayload(
                            token = body.token,
                            livekitUrl = body.livekitUrl,
                            roomId = body.roomId,
                            roomName = body.roomName,
                            diagnosticsEnabled = body.diagnosticsEnabled,
                            testNumber = body.testNumber
                        )
                    )
                    activeRideRepository.goLive()
                    onStartSuccess()
                } else {
                    _error.value = "Failed to start ride: ${res.errorBody()?.string() ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun leaveRoom(roomRef: String, onLeaveSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                apiService.leaveLobby(roomRef)
            } catch (_: Exception) {}
            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.LEFT)
            onLeaveSuccess()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

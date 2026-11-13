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

sealed class LobbyLoadState {
    object Loading : LobbyLoadState()
    data class Ready(val status: LobbyStatus) : LobbyLoadState()
    data class Retrying(val attempt: Int, val maxAttempts: Int = 5, val message: String? = null) : LobbyLoadState()
    data class Failed(
        val reason: String,
        val isRetryable: Boolean = true,
        val isSchemaDegraded: Boolean = false,
        val requestId: String? = null
    ) : LobbyLoadState()
    data class Terminal(val terminal: LobbyViewModel.Terminal) : LobbyLoadState()
}

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val apiService: ApiService,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository,
    private val sessionHolder: com.ridervoice.mesh.session.OfflineSessionHolder = com.ridervoice.mesh.session.OfflineSessionHolder()
) : ViewModel() {

    private val _loadState = MutableStateFlow<LobbyLoadState>(LobbyLoadState.Loading)
    val loadState: StateFlow<LobbyLoadState> = _loadState.asStateFlow()

    private val _lobbyStatus = MutableStateFlow<LobbyStatus?>(null)
    val lobbyStatus: StateFlow<LobbyStatus?> = _lobbyStatus.asStateFlow()

    val activeRideState = activeRideRepository.state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _uiMessage = MutableStateFlow<com.ridervoice.ui.components.UiMessage?>(null)
    val uiMessage: StateFlow<com.ridervoice.ui.components.UiMessage?> = _uiMessage.asStateFlow()

    /** Why polling stopped for good, or null while the room is still live/pollable (H3). */
    enum class Terminal { ENDED, REMOVED, NOT_FOUND }

    private val _terminal = MutableStateFlow<Terminal?>(null)
    val terminal: StateFlow<Terminal?> = _terminal.asStateFlow()

    private var pollingJob: Job? = null

    private fun computeBackoff(attempt: Int): Long {
        return when (attempt) {
            1 -> 2000L
            2 -> 4000L
            3 -> 8000L
            else -> 15000L
        }
    }

    fun startPolling(roomName: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var consecutiveErrors = 0
            var authRefreshedOnce = false
            var reportedConfigStatus: String? = null
            if (_lobbyStatus.value == null) {
                _loadState.value = LobbyLoadState.Loading
            }

            while (isActive) {
                var delayMs = 3000L
                try {
                    com.ridervoice.diagnostics.PowerProbe.recordPoll()
                    val response = apiService.getLobbyStatus(roomName)
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        consecutiveErrors = 0
                        _lobbyStatus.value = body
                        _loadState.value = LobbyLoadState.Ready(body)
                        _error.value = null

                        val statusBackupCode = body.offlineBackupCode
                        if (!statusBackupCode.isNullOrBlank() && statusBackupCode.length == 16) {
                            sessionHolder.provisionFromBackupCode(statusBackupCode)
                            if (activeRideRepository.state.value?.meshConfig == null) {
                                activeRideRepository.setMeshConfig(
                                    com.ridervoice.mesh.RoomMeshConfig(
                                        offlineMeshEnabled = body.offlineMeshEnabled,
                                        automaticFallbackEnabled = true,
                                        warmStandbyEnabled = true,
                                        configCompleted = true
                                    )
                                )
                            }
                            if (body.offlineMeshEnabled && reportedConfigStatus != "READY") {
                                reportedConfigStatus = "READY"
                                launch {
                                    try {
                                        apiService.reportOfflineConfig(roomName, com.ridervoice.models.OfflineConfigRequest("READY"))
                                    } catch (_: Exception) {}
                                }
                            }
                        } else if (!body.offlineMeshEnabled && reportedConfigStatus != "OPTED_OUT") {
                            reportedConfigStatus = "OPTED_OUT"
                            launch {
                                try {
                                    apiService.reportOfflineConfig(roomName, com.ridervoice.models.OfflineConfigRequest("OPTED_OUT"))
                                } catch (_: Exception) {}
                            }
                        }

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
                                    val tokenRes = apiService.getJoinToken(
                                        com.ridervoice.models.JoinTokenRequest(
                                            roomName = if (body.roomId != null) null else body.convoyName,
                                            roomId = body.roomId
                                        )
                                    )
                                    val tb = tokenRes.body()
                                    if (tokenRes.isSuccessful && tb != null) {
                                        val backupCode = tb.offlineBackupCode ?: body.offlineBackupCode
                                        if (!backupCode.isNullOrBlank() && backupCode.length == 16) {
                                            sessionHolder.provisionFromBackupCode(backupCode)
                                        }
                                        activeRideRepository.applyTokenPayload(
                                            com.ridervoice.models.TokenPayload(
                                                token = tb.token,
                                                livekitUrl = tb.livekitUrl,
                                                roomId = tb.roomId ?: body.roomId,
                                                roomName = tb.roomName ?: body.convoyName,
                                                lkRoomName = tb.lkRoomName ?: body.lkRoomName,
                                                offlineBackupCode = backupCode,
                                                diagnosticsEnabled = tb.diagnosticsEnabled,
                                                testNumber = tb.testNumber
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } else {
                        val code = response.code()
                        when (code) {
                            410 -> {
                                activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.ENDED_BY_HOST)
                                _terminal.value = Terminal.ENDED
                                _loadState.value = LobbyLoadState.Terminal(Terminal.ENDED)
                                val msg = com.ridervoice.ui.components.UiMessageMapper.map(410)
                                _uiMessage.value = msg
                                _error.value = msg.message
                                break
                            }
                            403 -> {
                                activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.REMOVED)
                                _terminal.value = Terminal.REMOVED
                                _loadState.value = LobbyLoadState.Terminal(Terminal.REMOVED)
                                val msg = com.ridervoice.ui.components.UiMessageMapper.map(403, "REMOVED_BY_HOST")
                                _uiMessage.value = msg
                                _error.value = msg.message
                                break
                            }
                            404 -> {
                                activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.NOT_FOUND)
                                _terminal.value = Terminal.NOT_FOUND
                                _loadState.value = LobbyLoadState.Terminal(Terminal.NOT_FOUND)
                                val msg = com.ridervoice.ui.components.UiMessageMapper.map(404)
                                _uiMessage.value = msg
                                _error.value = msg.message
                                break
                            }
                            401 -> {
                                if (!authRefreshedOnce) {
                                    authRefreshedOnce = true
                                    val refreshed = activeRideRepository.ensureFreshToken().isSuccess
                                    if (refreshed) {
                                        delay(1000L)
                                        continue
                                    }
                                }
                                val msg = "Session expired. Please sign in again."
                                _error.value = msg
                                val reqId = response.headers()["X-Request-Id"]
                                _loadState.value = LobbyLoadState.Failed(msg, isRetryable = false, requestId = reqId)
                                break
                            }
                            429 -> {
                                consecutiveErrors++
                                val reqId = response.headers()["X-Request-Id"]
                                val retryAfterSec = response.headers()["Retry-After"]?.toLongOrNull() ?: 5L
                                delayMs = (retryAfterSec * 1000L).coerceAtLeast(2000L)
                                val msg = "Rate limited. Retrying..."
                                _error.value = msg
                                if (_lobbyStatus.value == null && consecutiveErrors >= 5) {
                                    _loadState.value = LobbyLoadState.Failed(msg, isRetryable = true, requestId = reqId)
                                } else if (_lobbyStatus.value == null) {
                                    _loadState.value = LobbyLoadState.Retrying(consecutiveErrors, 5, msg)
                                }
                            }
                            503 -> {
                                consecutiveErrors++
                                val headerReqId = response.headers()["X-Request-Id"]
                                val errStr = try { response.errorBody()?.string() ?: "" } catch (_: Exception) { "" }
                                val bodyReqId = try { org.json.JSONObject(errStr).optString("requestId", null) } catch (_: Exception) { null }
                                val reqId = headerReqId ?: bodyReqId
                                val isDegraded = errStr.contains("SCHEMA_DEGRADED") || errStr.contains("schema")
                                val reason = if (isDegraded) "Service is updating, try again shortly" else "Server temporarily unavailable"
                                _error.value = reason
                                delayMs = computeBackoff(consecutiveErrors)
                                val elapsed = System.currentTimeMillis() - startTime
                                if (_lobbyStatus.value == null && (consecutiveErrors >= 5 || elapsed >= 30000L)) {
                                    _loadState.value = LobbyLoadState.Failed(reason, isRetryable = true, isSchemaDegraded = isDegraded, requestId = reqId)
                                } else if (_lobbyStatus.value == null) {
                                    _loadState.value = LobbyLoadState.Retrying(consecutiveErrors, 5, reason)
                                }
                            }
                            else -> {
                                consecutiveErrors++
                                val headerReqId = response.headers()["X-Request-Id"]
                                val errStr = try { response.errorBody()?.string() ?: "" } catch (_: Exception) { "" }
                                val bodyReqId = try { org.json.JSONObject(errStr).optString("requestId", null) } catch (_: Exception) { null }
                                val reqId = headerReqId ?: bodyReqId
                                val reason = "Server error ($code)"
                                _error.value = reason
                                delayMs = computeBackoff(consecutiveErrors)
                                val elapsed = System.currentTimeMillis() - startTime
                                if (_lobbyStatus.value == null && (consecutiveErrors >= 5 || elapsed >= 30000L)) {
                                    _loadState.value = LobbyLoadState.Failed(reason, isRetryable = true, requestId = reqId)
                                } else if (_lobbyStatus.value == null) {
                                    _loadState.value = LobbyLoadState.Retrying(consecutiveErrors, 5, reason)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    val reason = e.localizedMessage ?: "Network connection issue"
                    val msg = com.ridervoice.ui.components.UiMessageMapper.map(500, fallback = reason)
                    _uiMessage.value = msg
                    _error.value = msg.message
                    delayMs = computeBackoff(consecutiveErrors)
                    val elapsed = System.currentTimeMillis() - startTime
                    if (_lobbyStatus.value == null && (consecutiveErrors >= 5 || elapsed >= 30000L)) {
                        _loadState.value = LobbyLoadState.Failed(reason, isRetryable = true)
                    } else if (_lobbyStatus.value == null) {
                        _loadState.value = LobbyLoadState.Retrying(consecutiveErrors, 5, reason)
                    }
                }
                delay(delayMs)
            }
        }
    }

    fun retryPolling(roomName: String) {
        _loadState.value = LobbyLoadState.Loading
        startPolling(roomName)
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
                val body = res.body()
                if (res.isSuccessful && body != null) {
                    _shareLink.value = body.shareUrl
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

    fun toggleOfflineMesh(enabled: Boolean) {
        if (enabled) {
            activeRideRepository.setMeshConfig(
                com.ridervoice.mesh.RoomMeshConfig(
                    offlineMeshEnabled = true,
                    automaticFallbackEnabled = true,
                    warmStandbyEnabled = true,
                    configCompleted = false
                )
            )
            viewModelScope.launch {
                delay(1000L)
                val current = activeRideRepository.state.value?.meshConfig
                if (current != null && current.offlineMeshEnabled) {
                    activeRideRepository.setMeshConfig(current.copy(configCompleted = true))
                }
            }
        } else {
            activeRideRepository.setMeshConfig(com.ridervoice.mesh.RoomMeshConfig.DISABLED)
        }
    }

    fun toggleAutomaticFallback(enabled: Boolean) {
        val current = activeRideRepository.state.value?.meshConfig ?: return
        activeRideRepository.setMeshConfig(current.copy(automaticFallbackEnabled = enabled))
    }

    fun toggleWarmStandby(enabled: Boolean) {
        val current = activeRideRepository.state.value?.meshConfig ?: return
        activeRideRepository.setMeshConfig(current.copy(warmStandbyEnabled = enabled))
    }

    fun toggleRoomOfflineMesh(roomName: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                apiService.toggleOfflineMesh(roomName, com.ridervoice.models.ToggleOfflineMeshRequest(enabled))
                val res = apiService.getLobbyStatus(roomName)
                if (res.isSuccessful && res.body() != null) {
                    _lobbyStatus.value = res.body()
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage
            }
        }
    }

    fun startRide(roomName: String, allowSolo: Boolean = false, onStartSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.startRide(roomName, com.ridervoice.models.StartRideRequest(allowSolo = allowSolo))
                val body = res.body()
                if (res.isSuccessful && body != null) {
                    val backupCode = body.offlineBackupCode
                    if (!backupCode.isNullOrBlank() && backupCode.length == 16) {
                        sessionHolder.provisionFromBackupCode(backupCode)
                    }
                    val currentMeshConfig = activeRideRepository.state.value?.meshConfig
                    if (currentMeshConfig?.offlineMeshEnabled == true) {
                        activeRideRepository.setMeshConfig(currentMeshConfig.copy(configCompleted = true))
                    }
                    activeRideRepository.applyTokenPayload(
                        com.ridervoice.models.TokenPayload(
                            token = body.token,
                            livekitUrl = body.livekitUrl,
                            roomId = body.roomId,
                            roomName = body.roomName,
                            lkRoomName = body.lkRoomName,
                            offlineBackupCode = body.offlineBackupCode,
                            diagnosticsEnabled = body.diagnosticsEnabled,
                            testNumber = body.testNumber
                        )
                    )
                    activeRideRepository.goLive()
                    onStartSuccess()
                } else {
                    val raw = try { res.errorBody()?.string() } catch (_: Exception) { null }
                    val msg = raw?.let {
                        try { org.json.JSONObject(it).optString("error", null) } catch (_: Exception) { null }
                    } ?: "Failed to start ride (${res.code()})"
                    _error.value = msg
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun leaveRoom(roomRef: String, onLeaveSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                sessionHolder.clearBackup()
                sessionHolder.clearCurrent()
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

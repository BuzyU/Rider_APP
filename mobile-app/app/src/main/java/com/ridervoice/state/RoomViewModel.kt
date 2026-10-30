package com.ridervoice.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.HardwarePTTManager
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.audio.VoxEngine
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.network.ApiService
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkHealth
import com.ridervoice.network.NetworkResilienceManager
import com.ridervoice.services.LocationService
import com.ridervoice.services.RideRecorder
import com.ridervoice.services.ServiceWatchdog
import com.ridervoice.services.ThermalManager
import com.ridervoice.ui.components.UiMessage
import com.ridervoice.ui.components.UiMessageMapper
import com.ridervoice.utils.Constants
import com.ridervoice.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val apiService: ApiService,
    private val liveKitManager: LiveKitManager,
    private val locationService: LocationService,
    private val audioDeviceRouter: AudioDeviceRouter,
    private val voxEngine: VoxEngine,
    private val networkResilienceManager: NetworkResilienceManager,
    private val rideRecorder: RideRecorder,
    private val thermalManager: ThermalManager,
    private val serviceWatchdog: ServiceWatchdog,
    private val securePrefs: SecurePreferences,
    private val hardwarePTTManager: HardwarePTTManager,
    private val networkDiagnosticsCollector: com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository,
    private val audioSettingsApplier: com.ridervoice.audio.AudioSettingsApplier? = null
) : ViewModel() {

    val activeRide: StateFlow<com.ridervoice.state.ActiveRideRepository.ActiveRide?> = activeRideRepository.state
    val isCollectingDiagnostics: StateFlow<Boolean> = networkDiagnosticsCollector.isCollecting
    val latestSelfMetric: StateFlow<com.ridervoice.diagnostics.models.SelfMetricSample?> = networkDiagnosticsCollector.latestSelfMetric
    val diagSampleCount: StateFlow<Int> = networkDiagnosticsCollector.sampleCount
    val connectionState: StateFlow<ConnectionState> = liveKitManager.connectionState
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = liveKitManager.remoteLocations
    val activeSpeaker: StateFlow<String?> = liveKitManager.activeSpeaker
    val currentLocation: StateFlow<android.location.Location?> = locationService.currentLocation
    val networkHealth: StateFlow<NetworkHealth> = networkResilienceManager.networkHealth
    val isMicEnabled: StateFlow<Boolean> = liveKitManager.isMicEnabled
    val deafened: StateFlow<Boolean> = liveKitManager.deafened

    val activeAudioDevice: StateFlow<AudioDevice> = audioDeviceRouter.activeDevice
    val audioRouterState: StateFlow<RouterState> = audioDeviceRouter.routerState
    val requestedAudioDevice: StateFlow<AudioDevice?> = audioDeviceRouter.requestedDevice
    val routedInput: StateFlow<RoutedDeviceInfo> = audioDeviceRouter.routedInput
    val routedOutput: StateFlow<RoutedDeviceInfo> = audioDeviceRouter.routedOutput

    val isVoxOpen: StateFlow<Boolean> = voxEngine.isMicOpen
    val noiseFloor: StateFlow<Float> = voxEngine.noiseFloor
    val currentAmplitude: StateFlow<Float> = voxEngine.currentAmplitude

    val audioStatusLine: StateFlow<String> = combine(
        activeAudioDevice,
        connectionState,
        isMicEnabled,
        isVoxOpen
    ) { device, conn, micOn, voxOpen ->
        when {
            conn != ConnectionState.CONNECTED -> "Not connected"
            voxOpen || micOn -> "Speaking via ${device.displayName()}"
            else -> "Listening · ${device.displayName()}"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Initialising audio…")

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage

    val convoyEnded: StateFlow<Boolean> = liveKitManager.convoyEnded
    val inRideSosAlert: StateFlow<com.ridervoice.network.InRideSosAlert?> = liveKitManager.inRideSosAlert

    fun dismissInRideSosAlert() {
        liveKitManager.dismissInRideSos()
    }



    init {
        liveKitManager.participants
            .onEach { incoming ->
                val current = _participants.value.toMutableList()
                val incomingIds = incoming.map { it.identity }.toSet()

                current.removeAll { !incomingIds.contains(it.identity) }

                incoming.forEach { incomingParticipant ->
                    val idx = current.indexOfFirst { it.identity == incomingParticipant.identity }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(
                            isGhost = false,
                            disconnectedAt = null,
                            displayName = incomingParticipant.displayName ?: current[idx].displayName
                        )
                    } else {
                        current.add(incomingParticipant.copy(isGhost = false))
                    }
                }
                _participants.value = current
            }
            .launchIn(viewModelScope)

        locationService.currentLocation
            .filterNotNull()
            .onEach { loc ->
                val myIdentity = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@onEach

                voxEngine.updateSpeed(loc.speed)

                if (liveKitManager.connectionState.value == ConnectionState.CONNECTED) {
                    liveKitManager.publishLocation(
                        RiderLocation(
                            riderId = myIdentity,
                            lat = loc.latitude,
                            lng = loc.longitude,
                            speed = loc.speed,
                            heading = loc.bearing,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            .launchIn(viewModelScope)

        networkResilienceManager.networkHealth
            .onEach { health ->
                locationService.setNetworkDegraded(health == NetworkHealth.DEGRADED)
                if (health == NetworkHealth.CONNECTED) {
                    liveKitManager.onNetworkRestored()
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000)
                if (activeRideRepository.state.value == null) continue
                val now = System.currentTimeMillis()
                _participants.value = _participants.value.filter { p ->
                    if (p.isGhost && p.disconnectedAt != null) {
                        (now - p.disconnectedAt) < (2 * 60 * 1000)
                    } else true
                }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(30_000)
                val ride = activeRideRepository.state.value ?: continue
                val active = ride.roomId.ifBlank { ride.displayName }
                if (!ride.isHost && active.isNotBlank() && !liveKitManager.convoyEnded.value) {
                    try {
                        val res = apiService.getLobbyStatus(active)
                        if (res.code() == 404 || res.code() == 410) {
                            liveKitManager.signalConvoyEnded()
                            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.ENDED_BY_HOST)
                        } else if (res.code() == 403) {
                            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.REMOVED)
                            liveKitManager.disconnect()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        viewModelScope.launch {
            liveKitManager.roomFault.collect { fault ->
                when (fault) {
                    is com.ridervoice.state.RoomFault.ConfirmedRoomMismatch -> {
                        val msg = UiMessage(
                            title = "Room Mismatch",
                            message = "Unable to join convoy audio: room identity mismatch. Please re-join the ride.",
                            actionText = "Go home",
                            isFatal = true
                        )
                        _uiMessage.value = msg
                        _error.value = msg.message
                    }
                    is com.ridervoice.state.RoomFault.RemovedByHost -> {
                        val msg = UiMessage(
                            title = "Removed",
                            message = "You were removed from this convoy by the host.",
                            actionText = "Go home",
                            isFatal = true
                        )
                        _uiMessage.value = msg
                    }
                    is com.ridervoice.state.RoomFault.RoomEnded -> {
                        val msg = UiMessage(
                            title = "Ride Ended",
                            message = "The convoy ride has ended.",
                            actionText = "Go home",
                            isFatal = true
                        )
                        _uiMessage.value = msg
                    }
                    else -> {}
                }
            }
        }
    }

    private var isJoining = false

    fun joinRoom(roomName: String, userName: String) {
        val ride = activeRideRepository.state.value
        val effectiveRoomId = ride?.roomId?.takeIf { it.isNotBlank() } ?: roomName
        if (isJoining) return
        if (liveKitManager.connectionState.value == ConnectionState.CONNECTED) {
            if (liveKitManager.currentRoomId == effectiveRoomId) {
                return
            }
            // Connected to a different ride session! Disconnect first before proceeding.
            liveKitManager.disconnect()
        }
        isJoining = true
        _participants.value = emptyList()

        hardwarePTTManager.onMicToggleRequest = { open ->
            liveKitManager.onPttPressed(open)
        }
        hardwarePTTManager.activateSession()

        applyAudioSettings()

        viewModelScope.launch {
            _error.value = null
            _uiMessage.value = null
            try {
                val tokenResult = activeRideRepository.ensureFreshToken()
                val token = tokenResult.getOrThrow()
                val currentRide = activeRideRepository.state.value
                val effectiveUrl = currentRide?.livekitUrl?.takeIf { it.isNotBlank() } ?: Constants.LIVEKIT_URL

                if (currentRide?.diagnosticsEnabled == true) {
                    securePrefs.setNetworkDiagnosticsEnabled(true)
                }

                liveKitManager.connect(
                    url = effectiveUrl,
                    roomId = effectiveRoomId,
                    tokenProvider = {
                        activeRideRepository.ensureFreshToken().getOrThrow()
                    },
                    expectedLkRoomName = currentRide?.takeIf { it.roomId == effectiveRoomId }?.lkRoomName
                )
            } catch (e: Exception) {
                val msg = UiMessageMapper.map(500, fallback = "Connection error: ${e.message}")
                _uiMessage.value = msg
                _error.value = msg.message
            } finally {
                isJoining = false
            }
        }
    }

    fun onPttPressed(pressed: Boolean) {
        liveKitManager.onPttPressed(pressed)
    }

    fun toggleMute() {
        liveKitManager.setUserMuted(!liveKitManager.isUserMuted.value)
    }

    fun setDeafened(deafened: Boolean) {
        liveKitManager.setDeafened(deafened)
    }

    private fun applyAudioSettings() {
        if (audioSettingsApplier != null) {
            audioSettingsApplier.applyAll()
            return
        }
        val sensitivity = when (securePrefs.getString("settings_vox_sensitivity", "Medium")) {
            "Low" -> 0.2f
            "High" -> 0.8f
            else -> 0.5f
        }
        voxEngine.setSensitivity(sensitivity)
        voxEngine.setVoxEnabled(securePrefs.getBoolean("settings_open_mic", true))
        liveKitManager.noiseSuppressionEnabled = securePrefs.getBoolean("settings_noise_cancellation", true)
        val hardwarePttEnabled = securePrefs.getBoolean("settings_hardware_ptt", true)
        val hybridVoxEnabled = securePrefs.getBoolean("settings_hybrid_vox", true)
        voxEngine.setHardwarePttEnabled(hardwarePttEnabled)
        voxEngine.setHybridVoxMode(hybridVoxEnabled)
    }

    fun setVoxSensitivity(sensitivity: Float) {
        voxEngine.setSensitivity(sensitivity)
    }

    fun setVoxEnabled(enabled: Boolean) {
        voxEngine.setVoxEnabled(enabled)
    }

    fun rescanAudioDevices() {
        audioDeviceRouter.reEvaluatePriority()
    }

    fun setAudioRoute(routeType: String) {
        audioDeviceRouter.setAudioRoute(routeType)
    }

    fun clearError() {
        _error.value = null
        _uiMessage.value = null
    }

    fun setError(msg: String) {
        _error.value = msg
        _uiMessage.value = UiMessageMapper.map(400, fallback = msg)
    }

    fun isDiagnosticsConsentShown(): Boolean = securePrefs.isDiagnosticsConsentShown()

    fun setDiagnosticsConsentChoice(share: Boolean) {
        securePrefs.setDiagnosticsConsentShown(true)
        securePrefs.setShareDiagnosticsWhenHostEnabled(share)
    }

    fun endRideForEveryone(roomName: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.endRideForEveryone(roomName)
                if (res.isSuccessful) {
                    try {
                        liveKitManager.broadcastConvoyEnded(roomName)
                    } catch (e: Exception) {
                        android.util.Log.w("RoomViewModel", "Failed to broadcast convoy ended: ${e.message}")
                    }
                    networkDiagnosticsCollector.stopSession()
                    leaveRoom(roomName)
                    onComplete()
                } else {
                    val errorBody = res.errorBody()?.string()
                    val uiMsg = UiMessageMapper.map(
                        httpCode = res.code(),
                        errorCode = errorBody,
                        fallback = "Failed to end ride: ${res.message()}"
                    )
                    _uiMessage.value = uiMsg
                    _error.value = uiMsg.message
                }
            } catch (e: Exception) {
                android.util.Log.e("RoomViewModel", "Failed to end ride on server: ${e.message}")
                val uiMsg = UiMessageMapper.map(
                    httpCode = 500,
                    fallback = "Failed to end ride: ${e.message}"
                )
                _uiMessage.value = uiMsg
                _error.value = uiMsg.message
            }
        }
    }

    fun transferHostAndLeave(roomName: String, newHostId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.transferHost(roomName, com.ridervoice.models.TransferHostRequest(newHostId))
                if (res.isSuccessful) {
                    leaveRoom(roomName)
                    onComplete()
                } else {
                    val msg = UiMessageMapper.map(res.code(), res.errorBody()?.string(), fallback = "Failed to transfer host")
                    _uiMessage.value = msg
                    _error.value = msg.message
                }
            } catch (e: Exception) {
                android.util.Log.e("RoomViewModel", "Failed to transfer host on server: ${e.message}")
                val msg = UiMessageMapper.map(500, fallback = "Failed to transfer host: ${e.message}")
                _uiMessage.value = msg
                _error.value = msg.message
            }
        }
    }

    fun leaveRoom(roomName: String? = null) {
        val targetRoom = roomName ?: activeRideRepository.state.value?.roomId ?: activeRideRepository.state.value?.displayName
        viewModelScope.launch {
            try {
                networkDiagnosticsCollector.stopSession()
            } catch (e: Exception) {
                android.util.Log.w("RoomViewModel", "Failed to stop diagnostic collector: ${e.message}")
            }
            if (!targetRoom.isNullOrBlank()) {
                try {
                    apiService.leaveLobby(targetRoom)
                } catch (e: Exception) {
                    android.util.Log.w("RoomViewModel", "Failed to call leaveLobby: ${e.message}")
                }
            }
            activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.LEFT)
        }
        cleanup()
        liveKitManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        hardwarePTTManager.deactivateSession()
    }

    private fun cleanup() {
        hardwarePTTManager.deactivateSession()
    }
}

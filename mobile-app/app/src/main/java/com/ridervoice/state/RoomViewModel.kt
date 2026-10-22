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
    private val networkDiagnosticsCollector: com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector
) : ViewModel() {

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
            voxOpen || micOn -> "Transmitting via ${device.displayName()}"
            else -> "Listening · ${device.displayName()}"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Initialising audio…")

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val convoyEnded: StateFlow<Boolean> = liveKitManager.convoyEnded



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
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val now = System.currentTimeMillis()
                _participants.value = _participants.value.filter { p ->
                    if (p.isGhost && p.disconnectedAt != null) {
                        (now - p.disconnectedAt) < (2 * 60 * 1000)
                    } else true
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val active = com.ridervoice.models.RideSession.activeRoomName
                if (!com.ridervoice.models.RideSession.isHost && !active.isNullOrBlank() && !liveKitManager.convoyEnded.value) {
                    try {
                        val res = apiService.getLobbyStatus(active)
                        if (res.code() == 404) {
                            liveKitManager.signalConvoyEnded()
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    fun joinRoom(roomName: String, userName: String) {
        _participants.value = emptyList()
        locationService.startTracking()
        rideRecorder.startRecording(roomName)
        thermalManager.startMonitoring(viewModelScope)
        serviceWatchdog.startMonitoring(viewModelScope)

        hardwarePTTManager.onMicToggleRequest = { open ->
            liveKitManager.onPttPressed(open)
        }
        hardwarePTTManager.activateSession()

        applyAudioSettings()

        val existingToken = com.ridervoice.models.RideSession.livekitToken
        val isTokenForThisRoom = existingToken.isNotBlank() &&
            com.ridervoice.utils.JwtRoomClaimDecoder.validateRoom(existingToken, roomName)

        viewModelScope.launch {
            _error.value = null
            try {
                val cachedOk = existingToken.isNotBlank() &&
                    com.ridervoice.utils.JwtRoomClaimDecoder.validate(existingToken, roomName) ==
                    com.ridervoice.utils.JwtRoomClaimDecoder.TokenCheck.VALID
                val effectiveToken = if (cachedOk) {
                    existingToken
                } else {
                    val res = apiService.getRoomToken(com.ridervoice.models.RoomTokenRequest(roomName))
                    if (res.isSuccessful && res.body() != null) {
                        val body = res.body()!!
                        com.ridervoice.models.RideSession.livekitToken = body.token
                        com.ridervoice.models.RideSession.activeRoomName = body.roomName
                        body.roomId?.let { com.ridervoice.models.RideSession.activeRoomId = it }
                        body.livekitUrl?.takeIf { it.isNotBlank() }?.let {
                            com.ridervoice.models.RideSession.livekitUrl = it
                        }
                        com.ridervoice.models.RideSession.diagnosticsEnabled = body.diagnosticsEnabled
                        com.ridervoice.models.RideSession.testNumber = body.testNumber
                        if (body.diagnosticsEnabled) {
                            securePrefs.setNetworkDiagnosticsEnabled(true)
                        }
                        body.token
                    } else {
                        throw Exception("Could not fetch credentials for convoy $roomName (code ${res.code()})")
                    }
                }

                val canonicalRoom = com.ridervoice.utils.JwtRoomClaimDecoder.extractRoomName(effectiveToken) ?: roomName
                com.ridervoice.models.RideSession.activeRoomName = canonicalRoom

                val effectiveUrl = com.ridervoice.models.RideSession.livekitUrl.ifBlank { Constants.LIVEKIT_URL }
                liveKitManager.connect(effectiveUrl, effectiveToken)
            } catch (e: Exception) {
                _error.value = "Connection error: ${e.message}"
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
    }

    fun setError(msg: String) {
        _error.value = msg
    }

    fun isDiagnosticsConsentShown(): Boolean = securePrefs.isDiagnosticsConsentShown()

    fun setDiagnosticsConsentChoice(share: Boolean) {
        securePrefs.setDiagnosticsConsentShown(true)
        securePrefs.setShareDiagnosticsWhenHostEnabled(share)
    }

    fun endRideForEveryone(roomName: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                liveKitManager.broadcastConvoyEnded(roomName)
                networkDiagnosticsCollector.stopSession()
                apiService.endRideForEveryone(roomName)
            } catch (e: Exception) {
                android.util.Log.e("RoomViewModel", "Failed to end ride on server: ${e.message}")
            } finally {
                leaveRoom(roomName)
                onComplete()
            }
        }
    }

    fun transferHostAndLeave(roomName: String, newHostId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                apiService.transferHost(roomName, com.ridervoice.models.TransferHostRequest(newHostId))
            } catch (e: Exception) {
                android.util.Log.e("RoomViewModel", "Failed to transfer host on server: ${e.message}")
            } finally {
                leaveRoom(roomName)
                onComplete()
            }
        }
    }

    fun leaveRoom(roomName: String? = null) {
        viewModelScope.launch {
            try {
                networkDiagnosticsCollector.stopSession()
            } catch (e: Exception) {
                android.util.Log.w("RoomViewModel", "Failed to stop diagnostic collector: ${e.message}")
            }
        }
        cleanup()
        liveKitManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    private fun cleanup() {
        locationService.stopTracking()
        rideRecorder.stopRecording()
        thermalManager.stopMonitoring()
        serviceWatchdog.stopMonitoring()
        hardwarePTTManager.deactivateSession()
    }
}

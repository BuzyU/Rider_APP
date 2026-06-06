package com.ridervoice.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
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
    private val serviceWatchdog: ServiceWatchdog
) : ViewModel() {

    // ── LiveKit / connection ──────────────────────────────────────────────────

    val connectionState: StateFlow<ConnectionState> = liveKitManager.connectionState
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = liveKitManager.remoteLocations
    val networkHealth: StateFlow<NetworkHealth> = networkResilienceManager.networkHealth
    val isMicEnabled: StateFlow<Boolean> = liveKitManager.isMicEnabled

    // ── Audio device ──────────────────────────────────────────────────────────

    val activeAudioDevice: StateFlow<AudioDevice> = audioDeviceRouter.activeDevice
    val audioRouterState: StateFlow<RouterState> = audioDeviceRouter.routerState

    // ── VOX ───────────────────────────────────────────────────────────────────

    val isVoxOpen: StateFlow<Boolean> = voxEngine.isMicOpen
    val noiseFloor: StateFlow<Float> = voxEngine.noiseFloor
    val currentAmplitude: StateFlow<Float> = voxEngine.currentAmplitude

    // Derive a human-readable status line for the HUD
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

    // ── Participants (with Ghost Rider logic) ─────────────────────────────────

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var currentUserName = ""

    init {
        // Sync participant list with Ghost Rider state machine
        liveKitManager.participants
            .onEach { activeIdentities ->
                val now = System.currentTimeMillis()
                val current = _participants.value.toMutableList()

                // Mark absent riders as ghosts
                current.forEachIndexed { idx, p ->
                    if (!activeIdentities.contains(p.identity) && !p.isGhost) {
                        current[idx] = p.copy(isGhost = true, disconnectedAt = now)
                    }
                }
                // Add or un-ghost returning riders
                activeIdentities.forEach { identity ->
                    val idx = current.indexOfFirst { it.identity == identity }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(isGhost = false, disconnectedAt = null)
                    } else {
                        current.add(Participant(identity = identity))
                    }
                }
                _participants.value = current
            }
            .launchIn(viewModelScope)

        // Broadcast local GPS location when we have a fix and are connected
        locationService.currentLocation
            .filterNotNull()
            .onEach { loc ->
                if (liveKitManager.connectionState.value == ConnectionState.CONNECTED) {
                    liveKitManager.publishLocation(
                        RiderLocation(
                            riderId = currentUserName,
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

        // Throttle telemetry on weak network
        networkResilienceManager.networkHealth
            .onEach { health -> locationService.setNetworkDegraded(health == NetworkHealth.DEGRADED) }
            .launchIn(viewModelScope)

        // Ghost purge: remove ghosts after 15 minutes
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val now = System.currentTimeMillis()
                _participants.value = _participants.value.filter { p ->
                    if (p.isGhost && p.disconnectedAt != null) {
                        (now - p.disconnectedAt) < (15 * 60 * 1000)
                    } else true
                }
            }
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    fun joinRoom(roomName: String, userName: String) {
        currentUserName = userName
        locationService.startTracking()
        rideRecorder.startRecording(roomName)
        thermalManager.startMonitoring(viewModelScope)
        serviceWatchdog.startMonitoring(viewModelScope)

        val token = com.ridervoice.models.RideSession.livekitToken
        val url = com.ridervoice.models.RideSession.livekitUrl.ifBlank { Constants.LIVEKIT_URL }

        if (token.isBlank()) {
            _error.value = "Missing session credentials"
            return
        }

        viewModelScope.launch {
            _error.value = null
            try {
                // LiveKitManager.connect() starts AudioDeviceRouter + VoxEngine internally
                liveKitManager.connect(url, token)
            } catch (e: Exception) {
                _error.value = "Connection error: ${e.message}"
            }
        }
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    /**
     * Called by RoomScreen PTT button or HardwarePTTManager.
     * Delegates to LiveKitManager which delegates to VoxEngine.
     */
    fun onPttPressed(pressed: Boolean) {
        liveKitManager.onPttPressed(pressed)
    }

    /**
     * Legacy toggle-mute for the UI button.
     * If PTT/VOX is managing the mic, this is a manual override.
     */
    fun toggleMute() {
        viewModelScope.launch {
            val newState = !isMicEnabled.value
            liveKitManager.setMicrophoneEnabled(newState)
        }
    }

    // ── VOX sensitivity (from settings) ──────────────────────────────────────

    /**
     * @param sensitivity 0.0 = most sensitive, 1.0 = least sensitive
     */
    fun setVoxSensitivity(sensitivity: Float) {
        voxEngine.setSensitivity(sensitivity)
    }

    fun setVoxEnabled(enabled: Boolean) {
        voxEngine.setVoxEnabled(enabled)
    }

    // ── Force device re-scan (settings button) ────────────────────────────────

    fun rescanAudioDevices() {
        audioDeviceRouter.reEvaluatePriority()
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun leaveRoom() {
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
        // liveKitManager.disconnect() stops audioDeviceRouter + voxEngine internally
    }
}

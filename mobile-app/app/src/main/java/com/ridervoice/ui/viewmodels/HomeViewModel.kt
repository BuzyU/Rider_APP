package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.RouterState
import com.ridervoice.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val hasActiveRide: Boolean = false,
    val activeRideName: String = "",
    val activeRideSubtitle: String = "Start or join a ride to connect",
    val deviceName: String = "No Device",
    val deviceStatus: String = "Tap to configure",
    val isDeviceConnected: Boolean = false,
    val isDeviceConfigured: Boolean = false,
    val networkStatus: String = "Strong",
    val isNetworkStrong: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val securePrefs: SecurePreferences,
    private val audioDeviceRouter: AudioDeviceRouter,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository
) : ViewModel() {

    private val isConfigured = securePrefs.isDeviceConfigured()
    private val devName = securePrefs.getDeviceName() ?: "No Device"

    private val _uiState = MutableStateFlow(HomeState(
        isDeviceConfigured = isConfigured,
        deviceName = if (isConfigured) devName else "No Device",
        deviceStatus = if (isConfigured) "Configured (Disconnected)" else "Tap to configure"
    ))
    val uiState: StateFlow<HomeState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            audioDeviceRouter.routerState.collectLatest { state ->
                val configured = securePrefs.isDeviceConfigured()
                val isConnected = state == RouterState.ACTIVE

                _uiState.value = _uiState.value.copy(
                    isDeviceConnected = isConnected,
                    deviceStatus = when {
                        isConnected -> "Connected"
                        state == RouterState.CONNECTING_BT -> "Connecting..."
                        state == RouterState.SCANNING -> "Scanning..."
                        configured -> "Configured (Disconnected)"
                        else -> "Tap to configure"
                    }
                )
            }
        }

        viewModelScope.launch {
            activeRideRepository.state.collectLatest { ride ->
                val isLive = ride != null && ride.phase == com.ridervoice.state.ActiveRideRepository.Phase.LIVE
                val isLobby = ride != null && ride.phase == com.ridervoice.state.ActiveRideRepository.Phase.LOBBY
                _uiState.value = _uiState.value.copy(
                    hasActiveRide = isLive,
                    activeRideName = ride?.displayName ?: "",
                    activeRideSubtitle = when {
                        isLive -> "Currently sharing location & audio"
                        isLobby -> "Waiting in lobby"
                        else -> "Start or join a ride to connect"
                    }
                )
            }
        }
    }

    fun refreshDeviceState() {
        val configured = securePrefs.isDeviceConfigured()
        val name = securePrefs.getDeviceName() ?: "No Device"
        _uiState.value = _uiState.value.copy(
            isDeviceConfigured = configured,
            deviceName = if (configured) name else "No Device"
        )
    }

    fun refreshActiveRideState() {
        val ride = activeRideRepository.state.value
        val isLive = ride != null && ride.phase == com.ridervoice.state.ActiveRideRepository.Phase.LIVE
        val isLobby = ride != null && ride.phase == com.ridervoice.state.ActiveRideRepository.Phase.LOBBY
        _uiState.value = _uiState.value.copy(
            hasActiveRide = isLive,
            activeRideName = ride?.displayName ?: "",
            activeRideSubtitle = when {
                isLive -> "Currently sharing location & audio"
                isLobby -> "Waiting in lobby"
                else -> "Start or join a ride to connect"
            }
        )
    }

    fun updateNetworkStatus(isStrong: Boolean) {
        _uiState.value = _uiState.value.copy(
            isNetworkStrong = isStrong,
            networkStatus = if (isStrong) "Strong" else "Weak"
        )
    }

    fun setActiveRide(roomName: String, isHost: Boolean = true) {
        activeRideRepository.enterLobby(roomName, roomName, isHost)
        activeRideRepository.goLive()
    }

    fun clearActiveRide() {
        activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.LEFT)
    }
}

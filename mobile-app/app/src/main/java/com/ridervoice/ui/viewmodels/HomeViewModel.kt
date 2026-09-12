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
    private val audioDeviceRouter: AudioDeviceRouter
) : ViewModel() {

    private val isConfigured = securePrefs.isDeviceConfigured()
    private val devName = securePrefs.getDeviceName() ?: "No Device"
    private val activeRoom = securePrefs.getActiveRideRoom() ?: com.ridervoice.models.RideSession.activeRoomName

    private val _uiState = MutableStateFlow(HomeState(
        isDeviceConfigured = isConfigured,
        deviceName = if (isConfigured) devName else "No Device",
        deviceStatus = if (isConfigured) "Configured (Disconnected)" else "Tap to configure",
        hasActiveRide = !activeRoom.isNullOrBlank(),
        activeRideName = activeRoom ?: "",
        activeRideSubtitle = if (!activeRoom.isNullOrBlank()) "Currently sharing location & audio" else "Start or join a ride to connect"
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
        val room = securePrefs.getActiveRideRoom() ?: com.ridervoice.models.RideSession.activeRoomName
        _uiState.value = _uiState.value.copy(
            hasActiveRide = !room.isNullOrBlank(),
            activeRideName = room ?: "",
            activeRideSubtitle = if (!room.isNullOrBlank()) "Currently sharing location & audio" else "Start or join a ride to connect"
        )
    }

    fun updateNetworkStatus(isStrong: Boolean) {
        _uiState.value = _uiState.value.copy(
            isNetworkStrong = isStrong,
            networkStatus = if (isStrong) "Strong" else "Weak"
        )
    }

    fun setActiveRide(roomName: String, isHost: Boolean = true) {
        securePrefs.saveActiveRide(roomName, isHost)
        com.ridervoice.models.RideSession.activeRoomName = roomName
        com.ridervoice.models.RideSession.isHost = isHost
        _uiState.value = _uiState.value.copy(
            hasActiveRide = true,
            activeRideName = roomName,
            activeRideSubtitle = "Currently sharing location & audio"
        )
    }

    fun clearActiveRide() {
        securePrefs.clearActiveRide()
        com.ridervoice.models.RideSession.clear()
        _uiState.value = _uiState.value.copy(
            hasActiveRide = false,
            activeRideName = "",
            activeRideSubtitle = "Start or join a ride to connect"
        )
    }
}

package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.CancelAlertRequest
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.services.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SosState {
    object Idle : SosState()
    object Sending : SosState()
    data class Sent(val alertId: String? = null) : SosState()
    data class Failed(val message: String) : SosState()
}

@HiltViewModel
class SosViewModel @Inject constructor(
    private val apiService: ApiService,
    private val locationService: LocationService
) : ViewModel() {

    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()

    private var activeAlertId: String? = null

    fun sendAlert(roomName: String) {
        viewModelScope.launch {
            _state.value = SosState.Sending
            try {
                val loc = locationService.currentLocation.value
                val response = apiService.sendSosAlert(
                    SosAlertRequest(
                        roomName = roomName,
                        lat = loc?.latitude,
                        lng = loc?.longitude
                    )
                )
                if (response.isSuccessful && response.body() != null) {
                    val alertId = response.body()?.alertId
                    activeAlertId = alertId
                    _state.value = SosState.Sent(alertId)
                } else {
                    _state.value = SosState.Failed("Server error: ${response.code()}")
                }
            } catch (e: Exception) {
                _state.value = SosState.Failed(e.message ?: "Network error")
            }
        }
    }

    fun cancelAlert(roomName: String, reason: String = "FALSE_ALARM") {
        viewModelScope.launch {
            try {
                apiService.cancelEmergencyAlert(
                    CancelAlertRequest(
                        roomName = roomName,
                        alertId = activeAlertId,
                        reason = reason
                    )
                )
            } catch (_: Exception) {
            } finally {
                activeAlertId = null
                _state.value = SosState.Idle
            }
        }
    }
}

package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.services.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class SosState {
    object Idle : SosState()
    object Sending : SosState()
    object Sent : SosState()
    data class Failed(val message: String) : SosState()
}

@HiltViewModel
class SosViewModel @Inject constructor(
    private val apiService: ApiService,
    private val locationService: LocationService
) : ViewModel() {

    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state

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
                _state.value = if (response.isSuccessful) SosState.Sent else SosState.Failed("Server error")
            } catch (e: Exception) {
                _state.value = SosState.Failed(e.message ?: "Network error")
            }
        }
    }
}

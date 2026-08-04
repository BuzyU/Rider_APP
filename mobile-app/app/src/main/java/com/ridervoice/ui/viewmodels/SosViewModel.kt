package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SosViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    fun sendAlert(roomName: String) {
        viewModelScope.launch {
            try {
                // Sending alert with null lat/lng for now, or fetch location if location services are available.
                apiService.sendSosAlert(SosAlertRequest(roomName = roomName, lat = null, lng = null))
            } catch (e: Exception) {
                // Log or handle error if needed
            }
        }
    }
}

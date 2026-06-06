package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.LobbyStatus
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _lobbyStatus = MutableStateFlow<LobbyStatus?>(null)
    val lobbyStatus: StateFlow<LobbyStatus?> = _lobbyStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var isPolling = false

    fun startPolling(roomName: String) {
        isPolling = true
        viewModelScope.launch {
            while (isPolling) {
                try {
                    val response = apiService.getLobbyStatus(roomName)
                    if (response.isSuccessful) {
                        _lobbyStatus.value = response.body()
                    }
                } catch (e: Exception) {
                    _error.value = e.message
                }
                delay(3000) // Poll every 3 seconds
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    fun startRide(roomName: String, onStartSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = apiService.startRide(roomName)
                if (res.isSuccessful && res.body() != null) {
                    com.ridervoice.models.RideSession.livekitToken = res.body()!!.token
                    com.ridervoice.models.RideSession.livekitUrl = res.body()!!.livekitUrl
                    onStartSuccess()
                } else {
                    _error.value = "Failed to start ride"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
}

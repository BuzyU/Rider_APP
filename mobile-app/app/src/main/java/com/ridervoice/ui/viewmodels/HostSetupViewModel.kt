package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class HostSetupViewModel @Inject constructor(
    private val apiService: com.ridervoice.network.ApiService
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _createdConvoyName = MutableStateFlow<String?>(null)
    val createdConvoyName = _createdConvoyName.asStateFlow()

    fun createConvoy(
        convoyName: String,
        origin: String?,
        destination: String?,
        meetupPoint: String?,
        estimatedDurationMin: Int?
    ) {
        androidx.lifecycle.viewModelScope.launch {
            _isLoading.value = true
            try {
                val req = com.ridervoice.models.ConvoyCreateRequest(
                    convoyName, origin, destination, estimatedDurationMin, meetupPoint
                )
                val response = apiService.createConvoy(req)
                if (response.isSuccessful && response.body() != null) {
                    _createdConvoyName.value = response.body()!!.roomId
                } else {
                    _error.value = "Failed to create convoy"
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}

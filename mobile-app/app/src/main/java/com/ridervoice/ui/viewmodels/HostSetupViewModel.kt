package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.ConvoyCreateRequest
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HostSetupViewModel @Inject constructor(
    private val apiService: ApiService
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
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val req = ConvoyCreateRequest(
                    convoyName, origin, destination, estimatedDurationMin, meetupPoint
                )
                val response = apiService.createConvoy(req)
                if (response.isSuccessful && response.body() != null) {
                    // BUG FIX: Use convoyName (the human-readable name), NOT roomId (UUID).
                    // All downstream APIs (lobby status, start ride, invite friends) look up
                    // rooms by Room.name, not Room.id. Passing a UUID causes 404 everywhere.
                    _createdConvoyName.value = response.body()!!.convoyName
                } else {
                    val raw = response.errorBody()?.string()
                    _error.value = parseConvoyError(raw, response.code())
                }
            } catch (e: Exception) {
                _error.value = "Network error: ${e.localizedMessage ?: "Please check your connection and retry."}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseConvoyError(raw: String?, code: Int): String {
        if (!raw.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(raw)
                if (json.has("error")) {
                    val errorMsg = json.getString("error")
                    if (errorMsg.contains("Internal server error", ignoreCase = true)) {
                        return "The server encountered a problem creating your convoy. Please try again."
                    }
                    return errorMsg
                }
            } catch (_: Exception) {
                if (raw.contains("Internal server error", ignoreCase = true)) {
                    return "The server encountered a problem creating your convoy. Please try again."
                }
            }
        }
        return when (code) {
            400 -> "Invalid convoy details. Please check the convoy name and try again."
            401 -> "Your session has expired. Please sign in again."
            409 -> "A convoy with this name already exists. Please choose a different name."
            in 500..599 -> "Server trouble. Please try again shortly."
            else -> "Failed to create convoy (code $code). Please retry."
        }
    }
}

package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.ConvoyCreateRequest
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HostSetupViewModel @Inject constructor(
    private val apiService: ApiService,
    private val securePreferences: SecurePreferences,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository
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
        estimatedDurationMin: Int?,
        diagnosticsEnabled: Boolean = false,
        clientRequestId: String? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val req = ConvoyCreateRequest(
                    convoyName = convoyName,
                    origin = origin,
                    destination = destination,
                    estimatedDurationMin = estimatedDurationMin,
                    meetupPoint = meetupPoint,
                    diagnosticsEnabled = diagnosticsEnabled,
                    clientRequestId = clientRequestId
                )
                val response = apiService.createConvoy(req)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    activeRideRepository.enterLobby(
                        roomId = body.roomId,
                        name = body.convoyName,
                        isHost = true,
                        joinCode = body.joinCode
                    )
                    _createdConvoyName.value = body.convoyName
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
                        return "The server encountered a problem creating your ride. Please try again."
                    }
                    return errorMsg
                }
            } catch (_: Exception) {
                if (raw.contains("Internal server error", ignoreCase = true)) {
                    return "The server encountered a problem creating your ride. Please try again."
                }
            }
        }
        return when (code) {
            400 -> "Invalid ride details. Please check the ride name and try again."
            401 -> "Your session has expired. Please sign in again."
            409 -> "A ride with this name already exists. Please choose a different name."
            in 500..599 -> "Server trouble. Please try again shortly."
            else -> "Failed to create ride (code $code). Please retry."
        }
    }

    fun isDiagnosticsConsentShown(): Boolean = securePreferences.isDiagnosticsConsentShown()
    fun setDiagnosticsConsentShown(shown: Boolean) = securePreferences.setDiagnosticsConsentShown(shown)
}

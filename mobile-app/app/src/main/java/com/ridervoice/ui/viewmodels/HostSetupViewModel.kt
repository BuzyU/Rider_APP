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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HostSetupViewModel @Inject constructor(
    private val apiService: ApiService,
    private val securePreferences: SecurePreferences,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository
) : ViewModel() {

    sealed class CreateRideState {
        object Draft : CreateRideState()
        object Submitting : CreateRideState()
        data class UnknownOutcome(val clientRequestId: String, val message: String) : CreateRideState()
        data class Succeeded(val convoyName: String) : CreateRideState()
        data class Conflict(val message: String) : CreateRideState()
    }

    companion object {
        const val KEY_PENDING_CREATE_REQUEST_ID = "pending_create_request_id"
        const val KEY_PENDING_CREATE_NAME = "pending_create_name"
    }

    private val _createState = MutableStateFlow<CreateRideState>(CreateRideState.Draft)
    val createState = _createState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _createdConvoyName = MutableStateFlow<String?>(null)
    val createdConvoyName = _createdConvoyName.asStateFlow()

    fun getOrCreateClientRequestId(): String {
        val existing = securePreferences.getString(KEY_PENDING_CREATE_REQUEST_ID, "")
        if (existing.isNotBlank()) return existing
        val newId = UUID.randomUUID().toString()
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, newId)
        return newId
    }

    fun clearPendingAttempt() {
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, "")
        securePreferences.saveString(KEY_PENDING_CREATE_NAME, "")
        _createState.value = CreateRideState.Draft
    }

    private fun savePendingAttempt(requestId: String, name: String) {
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, requestId)
        securePreferences.saveString(KEY_PENDING_CREATE_NAME, name)
    }

    fun onNavigationHandled() {
        _createdConvoyName.value = null
        _createState.value = CreateRideState.Draft
    }

    fun createConvoy(
        convoyName: String,
        origin: String?,
        destination: String?,
        meetupPoint: String?,
        estimatedDurationMin: Int? = null,
        diagnosticsEnabled: Boolean = false,
        clientRequestId: String? = null
    ) {
        // Single-flight guard: prevent concurrent duplicate requests
        if (_createState.value is CreateRideState.Submitting || _isLoading.value) {
            return
        }

        val activeRequestId = clientRequestId ?: getOrCreateClientRequestId()
        savePendingAttempt(activeRequestId, convoyName)

        _createState.value = CreateRideState.Submitting
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val req = ConvoyCreateRequest(
                    convoyName = convoyName,
                    origin = origin,
                    destination = destination,
                    estimatedDurationMin = estimatedDurationMin,
                    meetupPoint = meetupPoint,
                    diagnosticsEnabled = diagnosticsEnabled,
                    clientRequestId = activeRequestId
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
                    clearPendingAttempt()
                    _createdConvoyName.value = body.convoyName
                    _createState.value = CreateRideState.Succeeded(body.convoyName)
                } else {
                    val raw = response.errorBody()?.string()
                    val errorMsg = parseConvoyError(raw, response.code())
                    _error.value = errorMsg
                    if (response.code() == 409) {
                        _createState.value = CreateRideState.Conflict(errorMsg)
                    } else {
                        _createState.value = CreateRideState.UnknownOutcome(activeRequestId, errorMsg)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "Network error: ${e.localizedMessage ?: "Please check your connection and retry."}"
                _error.value = errorMsg
                _createState.value = CreateRideState.UnknownOutcome(activeRequestId, errorMsg)
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

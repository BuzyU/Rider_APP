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
    data class Sent(val alertId: String? = null, val recipientsCount: Int = 0) : SosState()
    data class NotSent(
        val reason: String = "NO_ACTIVE_RIDE",
        val emergencyNumber: String = "112",
        val message: String = "SOS NOT SENT. Call $emergencyNumber now."
    ) : SosState()
    data class Failed(val message: String, val emergencyNumber: String = "112") : SosState()
}

@HiltViewModel
class SosViewModel(
    private val apiService: ApiService,
    private val locationService: LocationService?,
    private val locationProvider: (() -> Pair<Double?, Double?>)? = null
) : ViewModel() {

    @Inject
    constructor(
        apiService: ApiService,
        locationService: LocationService
    ) : this(apiService, locationService, null)

    constructor(
        apiService: ApiService,
        locationProvider: () -> Pair<Double?, Double?>
    ) : this(apiService, null, locationProvider)

    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()

    private var activeAlertId: String? = null

    fun sendAlert(roomName: String) {
        viewModelScope.launch {
            _state.value = SosState.Sending
            try {
                val (lat, lng) = locationProvider?.invoke() ?: run {
                    val loc = locationService?.currentLocation?.value
                    Pair(loc?.latitude, loc?.longitude)
                }
                val response = apiService.sendSosAlert(
                    SosAlertRequest(
                        roomName = roomName,
                        lat = lat,
                        lng = lng
                    )
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.sent) {
                        val alertId = body.alertId
                        activeAlertId = alertId
                        _state.value = SosState.Sent(alertId = alertId, recipientsCount = body.recipientsCount ?: 0)
                    } else {
                        val num = body.emergencyNumber ?: "112"
                        _state.value = SosState.NotSent(
                            reason = body.reason ?: "NO_ACTIVE_RIDE",
                            emergencyNumber = num,
                            message = "SOS NOT SENT. Call $num now."
                        )
                    }
                } else {
                    _state.value = SosState.NotSent(
                        reason = "SERVER_ERROR",
                        emergencyNumber = "112",
                        message = "SOS NOT SENT. Call 112 now."
                    )
                }
            } catch (e: Exception) {
                _state.value = SosState.NotSent(
                    reason = e.message ?: "NETWORK_ERROR",
                    emergencyNumber = "112",
                    message = "SOS NOT SENT. Call 112 now."
                )
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

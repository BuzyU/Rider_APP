package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.CancelAlertRequest
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.services.InMemorySosOutbox
import com.ridervoice.services.LocationService
import com.ridervoice.services.PersistentSosOutbox
import com.ridervoice.services.SosDeliveryPolicy
import com.ridervoice.services.SosOutbox
import com.ridervoice.services.SosOutcome
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
    private val locationProvider: (() -> Pair<Double?, Double?>)? = null,
    private val outbox: SosOutbox = InMemorySosOutbox()
) : ViewModel() {

    @Inject
    constructor(
        apiService: ApiService,
        locationService: LocationService,
        outbox: PersistentSosOutbox
    ) : this(apiService, locationService, null, outbox)

    constructor(
        apiService: ApiService,
        locationProvider: () -> Pair<Double?, Double?>
    ) : this(apiService, null, locationProvider, InMemorySosOutbox())

    constructor(
        apiService: ApiService,
        locationProvider: () -> Pair<Double?, Double?>,
        outbox: SosOutbox
    ) : this(apiService, null, locationProvider, outbox)

    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()

    private var activeAlertId: String? = null
    private var activeClientAlertId: String? = null

    private fun queuedState(offline: Boolean) = SosState.NotSent(
        reason = "QUEUED_OFFLINE",
        emergencyNumber = "112",
        message = if (offline) "Offline: SOS saved and will be sent automatically. Call 112 now."
        else "SOS saved and will be retried automatically. Call 112 now."
    )

    /** Retry everything in the outbox now (also scheduled via WorkManager on reconnect). */
    fun flushPendingAlerts() {
        viewModelScope.launch {
            for (req in outbox.pending()) {
                val outcome = try {
                    val res = apiService.sendSosAlert(req)
                    SosDeliveryPolicy.classify(res.code(), res.body()?.let { it.sent || it.alertId != null } == true)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    SosOutcome.RETRY
                }
                when (outcome) {
                    SosOutcome.DELIVERED, SosOutcome.DROP -> outbox.remove(req.clientAlertId)
                    SosOutcome.RETRY -> {}
                }
            }
            if (outbox.pending().isNotEmpty()) outbox.scheduleFlush()
        }
    }

    fun sendAlert(roomName: String) {
        viewModelScope.launch {
            _state.value = SosState.Sending
            val (lat, lng) = locationProvider?.invoke() ?: run {
                val loc = locationService?.currentLocation?.value
                Pair(loc?.latitude, loc?.longitude)
            }
            val alertReq = SosAlertRequest(roomName = roomName, lat = lat, lng = lng)
            activeClientAlertId = alertReq.clientAlertId
            try {
                val response = apiService.sendSosAlert(alertReq)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    if (body.sent) {
                        activeAlertId = body.alertId
                        _state.value = SosState.Sent(alertId = body.alertId, recipientsCount = body.recipientsCount ?: 0)
                    } else {
                        val num = body.emergencyNumber ?: "112"
                        _state.value = SosState.NotSent(
                            reason = body.reason ?: "NO_ACTIVE_RIDE",
                            emergencyNumber = num,
                            message = "SOS NOT SENT. Call $num now."
                        )
                    }
                } else if (SosDeliveryPolicy.classify(response.code()) == SosOutcome.RETRY) {
                    outbox.enqueue(alertReq)
                    outbox.scheduleFlush()
                    _state.value = queuedState(offline = false)
                } else {
                    // 4xx other than 429 can never succeed on retry (not a member, ended, bad request).
                    _state.value = SosState.NotSent(
                        reason = "SERVER_ERROR",
                        emergencyNumber = "112",
                        message = "SOS NOT SENT. Call 112 now."
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                outbox.enqueue(alertReq)
                outbox.scheduleFlush()
                throw ce
            } catch (_: Exception) {
                outbox.enqueue(alertReq)
                outbox.scheduleFlush()
                _state.value = queuedState(offline = true)
            }
        }
    }
    fun cancelAlert(roomName: String, reason: String = "FALSE_ALARM") {
        activeClientAlertId?.let { outbox.remove(it) }
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

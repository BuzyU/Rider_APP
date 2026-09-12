package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class RoutePlannerState(
    val origin: String = "",
    val destination: String = "",
    val routeName: String = "NO ROUTE",
    val distanceKm: String = "0",
    val duration: String = "00:00",
    val elevationGain: String = "0",
    val nearbyRiders: List<NearbyRider> = emptyList()
)

data class NearbyRider(
    val handle: String,
    val distanceKm: String,
    val isOnline: Boolean = true
)

@HiltViewModel
class RoutePlannerViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(RoutePlannerState())
    val uiState: StateFlow<RoutePlannerState> = _uiState.asStateFlow()

    init {

        _uiState.value = _uiState.value.copy(
            nearbyRiders = listOf(
                NearbyRider("MotoGhost", "2.4"),
                NearbyRider("ApexHunter", "5.1"),
                NearbyRider("TwistiesKing", "8.9")
            )
        )
    }

    fun updateOrigin(origin: String) {
        _uiState.value = _uiState.value.copy(origin = origin)
        recalculateRoute()
    }

    fun updateDestination(destination: String) {
        _uiState.value = _uiState.value.copy(destination = destination)
        recalculateRoute()
    }

    fun swapOriginAndDestination() {
        val currentOrigin = _uiState.value.origin
        val currentDest = _uiState.value.destination
        _uiState.value = _uiState.value.copy(origin = currentDest, destination = currentOrigin)
        recalculateRoute()
    }

    fun useCurrentLocation() {
        _uiState.value = _uiState.value.copy(origin = "Current GPS Position (18.74° N, 73.40° E)")
        recalculateRoute()
    }

    fun setDestinationFromRider(rider: NearbyRider) {
        _uiState.value = _uiState.value.copy(destination = "Waypoint: @${rider.handle} (${rider.distanceKm} km)")
        recalculateRoute()
    }

    fun clearRoute() {
        _uiState.value = _uiState.value.copy(
            origin = "",
            destination = "",
            routeName = "NO ROUTE",
            distanceKm = "0",
            duration = "00:00",
            elevationGain = "0"
        )
    }

    fun saveRoute(onSaved: () -> Unit) {

        onSaved()
    }

    private fun recalculateRoute() {
        val state = _uiState.value
        if (state.origin.isNotBlank() && state.destination.isNotBlank()) {

            _uiState.value = state.copy(
                routeName = "${state.destination.take(20).uppercase()} RUN",
                distanceKm = "123",
                duration = "02:40",
                elevationGain = "1420"
            )
        } else {
            _uiState.value = state.copy(
                routeName = "NO ROUTE",
                distanceKm = "0",
                duration = "00:00",
                elevationGain = "0"
            )
        }
    }
}

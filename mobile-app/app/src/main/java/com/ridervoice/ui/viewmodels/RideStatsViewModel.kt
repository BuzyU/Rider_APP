package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.data.local.RideDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale

data class RideStatsState(
    val totalDistance: String = "0.0",
    val totalTime: String = "00:00:00",
    val avgSpeed: String = "0",
    val topSpeed: String = "0",
    val speedDataPoints: List<Float> = emptyList()
)

@HiltViewModel
class RideStatsViewModel @Inject constructor(
    private val rideDao: RideDao,
    private val apiService: com.ridervoice.network.ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(RideStatsState())
    val uiState: StateFlow<RideStatsState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val maxSpeedMps = rideDao.getAllTimeMaxSpeed() ?: 0f
            val topSpeedKmh = maxSpeedMps * 3.6f
            val topSpeedStr = String.format(Locale.US, "%.0f", topSpeedKmh)

            rideDao.getAllSessions().collectLatest { sessions ->
                var totalDistMeters = 0f
                var totalDurationSec = 0L

                for (session in sessions) {
                    totalDistMeters += session.totalDistanceMeters
                    val end = session.endTime ?: System.currentTimeMillis()
                    totalDurationSec += (end - session.startTime) / 1000
                }

                val totalKm = totalDistMeters / 1000f
                val avgSpeedKmh = if (totalDurationSec > 0) (totalKm / (totalDurationSec / 3600f)) else 0f
                
                val hours = totalDurationSec / 3600
                val mins = (totalDurationSec % 3600) / 60
                val secs = totalDurationSec % 60
                val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

                _uiState.value = _uiState.value.copy(
                    totalDistance = String.format(Locale.US, "%.1f", totalKm),
                    totalTime = timeStr,
                    avgSpeed = String.format(Locale.US, "%.0f", avgSpeedKmh),
                    topSpeed = topSpeedStr,
                    speedDataPoints = emptyList() // Needs full waypoint query to plot
                )
            }
        }
        
        syncRides()
    }

    private fun syncRides() {
        viewModelScope.launch {
            try {
                val response = apiService.getRideHistory()
                if (response.isSuccessful) {
                    val rides = response.body() ?: emptyList()
                    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    
                    rides.forEach { rideResponse ->
                        try {
                            val startMs = formatter.parse(rideResponse.startTime)?.time ?: return@forEach
                            val endMs = rideResponse.endTime?.let { formatter.parse(it)?.time }
                            
                            val existing = rideDao.getSessionById(rideResponse.id)
                            val resolvedRoomName = rideResponse.roomName?.takeIf { it.isNotBlank() }
                                ?: existing?.roomName?.takeIf { it.isNotBlank() }
                                ?: "Unnamed Ride"

                            val entity = com.ridervoice.data.local.entities.RideSessionEntity(
                                id = rideResponse.id,
                                roomName = resolvedRoomName,
                                startTime = startMs,
                                endTime = endMs,
                                totalDistanceMeters = rideResponse.distanceKm * 1000f,
                                isSynced = true
                            )
                            rideDao.insertSession(entity)
                        } catch (e: Exception) {
                            // Date parse error for single ride, skip
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore sync errors for now
            }
        }
    }
}

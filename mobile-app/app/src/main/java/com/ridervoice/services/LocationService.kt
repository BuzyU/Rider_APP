package com.ridervoice.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            result.lastLocation?.let { loc ->
                _currentLocation.value = loc
                // Adaptive Interval Logic
                updateLocationRequest(loc.speed)
            }
        }
    }

    private var currentIntervalMs = 5000L
    private var isNetworkDegraded = false

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        
        _isTracking.value = true
        fusedLocationClient.requestLocationUpdates(
            createRequest(2000L), // Default high frequency
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun stopTracking() {
        _isTracking.value = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    
    fun setNetworkDegraded(degraded: Boolean) {
        if (isNetworkDegraded != degraded) {
            isNetworkDegraded = degraded
            // Re-evaluate current interval
            _currentLocation.value?.let { loc -> updateLocationRequest(loc.speed) }
        }
    }

    @SuppressLint("MissingPermission")
    fun updatePollingInterval(intervalMs: Long) {
        if (currentIntervalMs != intervalMs) {
            currentIntervalMs = intervalMs
            val request = createRequest(currentIntervalMs)
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest(speedMps: Float) {
        // Adaptive Telemetry: 
        // Stationary (< 1 m/s) -> 15s
        // City (< 15 m/s) -> 5s
        // Highway (> 15 m/s) -> 2s
        var newInterval = when {
            speedMps < 1.0f -> 15000L
            speedMps < 15.0f -> 5000L
            else -> 2000L
        }
        
        // Throttling for Weak Signal / Degraded Network
        if (isNetworkDegraded && newInterval < 10000L) {
            newInterval = 10000L // Clamp max rate to 10s to save failing bandwidth for Voice
        }

        if (newInterval != currentIntervalMs) {
            currentIntervalMs = newInterval
            val request = createRequest(currentIntervalMs)
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    private fun createRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(2f)
            .build()
    }
}

package com.ridervoice.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LocationService"
        private const val MIN_DISTANCE_METERS = 2f
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    @Volatile private var currentIntervalMs = 5_000L
    @Volatile private var isNetworkDegraded = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _currentLocation.value = loc
            maybeUpdateInterval(loc.speed)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (!hasPermission()) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted — cannot start tracking")
            return
        }
        if (_isTracking.value) return

        _isTracking.value = true
        currentIntervalMs = 5_000L
        updateLocationRequest(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
        Log.i(TAG, "✅ Location tracking started")
    }

    private var stationaryStartTime: Long = 0L
    private var isDeepStationarySleep = false

    fun stopTracking() {
        _isTracking.value = false
        stationaryStartTime = 0L
        isDeepStationarySleep = false
        fusedClient.removeLocationUpdates(locationCallback)
        Log.i(TAG, "Location tracking stopped")
    }

    fun setNetworkDegraded(degraded: Boolean) {
        if (isNetworkDegraded == degraded) return
        isNetworkDegraded = degraded

        _currentLocation.value?.let { maybeUpdateInterval(it.speed) }
    }

    @SuppressLint("MissingPermission")
    fun updatePollingInterval(intervalMs: Long) {
        if (!_isTracking.value || !hasPermission()) return
        if (intervalMs == currentIntervalMs && !isDeepStationarySleep) return

        currentIntervalMs = intervalMs
        updateLocationRequest(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
        Log.d(TAG, "Polling interval changed to ${intervalMs}ms")
    }

    @SuppressLint("MissingPermission")
    private fun maybeUpdateInterval(speedMps: Float) {
        if (!_isTracking.value || !hasPermission()) return
        val now = System.currentTimeMillis()

        if (speedMps < 1.0f) {
            if (stationaryStartTime == 0L) stationaryStartTime = now
            val elapsedStationary = now - stationaryStartTime

            if (elapsedStationary > 120_000L && !isDeepStationarySleep) {
                // Transition to deep stationary sleep
                isDeepStationarySleep = true
                currentIntervalMs = 60_000L
                updateLocationRequest(
                    priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    intervalMs = 60_000L
                )
                Log.i(TAG, "Entering stationary GPS power sleep (60s, balanced power)")
                return
            }
        } else {
            // Movement detected — immediately wake up
            stationaryStartTime = 0L
            if (isDeepStationarySleep) {
                isDeepStationarySleep = false
                Log.i(TAG, "Movement detected — waking GNSS to high accuracy")
            }
        }

        var desired = when {
            isDeepStationarySleep -> 60_000L
            speedMps < 1.0f       -> 15_000L
            speedMps < 15.0f      ->  5_000L
            else                  ->  2_000L
        }

        if (isNetworkDegraded && desired < 10_000L) {
            desired = 10_000L
        }

        if (desired == currentIntervalMs && !isDeepStationarySleep) return

        currentIntervalMs = desired
        updateLocationRequest(Priority.PRIORITY_HIGH_ACCURACY, desired)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest(priority: Int, intervalMs: Long) {
        fusedClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

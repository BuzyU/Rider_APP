package com.ridervoice.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.HandlerThread
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
    @ApplicationContext private val context: Context,
    private val thermalManager: ThermalManager
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
    @Volatile private var currentPriority = Priority.PRIORITY_HIGH_ACCURACY
    @Volatile private var isNetworkDegraded = false

    private var handlerThread: HandlerThread? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _currentLocation.value = loc
            maybeUpdateInterval(loc)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (!hasPermission()) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted - cannot start tracking")
            return
        }
        if (_isTracking.value) return

        _isTracking.value = true
        stationaryStartTime = 0L

        if (handlerThread == null) {
            handlerThread = HandlerThread("LocationHandlerThread").apply { start() }
        }

        currentIntervalMs = 5_000L
        currentPriority = Priority.PRIORITY_HIGH_ACCURACY
        updateLocationRequest(currentPriority, currentIntervalMs)
        Log.i(TAG, "Location tracking started on dedicated HandlerThread")
    }

    private var stationaryStartTime: Long = 0L

    fun stopTracking() {
        _isTracking.value = false
        stationaryStartTime = 0L
        fusedClient.removeLocationUpdates(locationCallback)
        handlerThread?.quitSafely()
        handlerThread = null
        Log.i(TAG, "Location tracking stopped")
    }

    fun setNetworkDegraded(degraded: Boolean) {
        if (isNetworkDegraded == degraded) return
        isNetworkDegraded = degraded
        _currentLocation.value?.let { maybeUpdateInterval(it) }
    }

    @SuppressLint("MissingPermission")
    fun updatePollingInterval(intervalMs: Long) {
        if (!_isTracking.value || !hasPermission()) return
        if (intervalMs == currentIntervalMs) return

        currentIntervalMs = intervalMs
        updateLocationRequest(currentPriority, currentIntervalMs)
        Log.d(TAG, "Polling interval changed to ${intervalMs}ms")
    }

    @SuppressLint("MissingPermission")
    private fun maybeUpdateInterval(loc: Location) {
        if (!_isTracking.value || !hasPermission()) return
        val now = System.currentTimeMillis()
        val speedMps = loc.speed
        val speedKmh = speedMps * 3.6f

        if (speedMps < 1.0f) {
            if (stationaryStartTime == 0L) stationaryStartTime = now
        } else {
            stationaryStartTime = 0L
        }

        val stationarySec = if (stationaryStartTime > 0L) ((now - stationaryStartTime) / 1000L).toInt() else 0
        val (batteryPct, charging) = readBatteryState()
        val thermal = thermalManager.thermalLevel.value

        var desiredInterval = LocationPolicy.intervalMs(speedKmh, stationarySec, thermal, batteryPct, charging)
        if (isNetworkDegraded && desiredInterval < 10_000L) {
            desiredInterval = 10_000L
        }
        val desiredPriority = LocationPolicy.priority(speedKmh, thermal)

        if (desiredInterval != currentIntervalMs || desiredPriority != currentPriority) {
            currentIntervalMs = desiredInterval
            currentPriority = desiredPriority
            updateLocationRequest(currentPriority, currentIntervalMs)
            com.ridervoice.utils.RvLog.loc("interval=$currentIntervalMs priority=$currentPriority thermal=$thermal speedKmh=$speedKmh")
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest(priority: Int, intervalMs: Long) {
        fusedClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()
        val looper = handlerThread?.looper ?: Looper.getMainLooper()
        fusedClient.requestLocationUpdates(request, locationCallback, looper)
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(null, filter)
            }
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else 50
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            pct to isCharging
        } catch (_: Exception) {
            50 to false
        }
    }

    private fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

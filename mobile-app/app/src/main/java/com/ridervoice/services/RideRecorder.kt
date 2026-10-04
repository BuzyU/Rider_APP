package com.ridervoice.services

import android.util.Log
import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RideSummary(
    val sessionId: String,
    val durationMinutes: Int,
    val distanceKm: Float,
    val topSpeedKmh: Float
)

@Singleton
class RideRecorder @Inject constructor(
    private val rideDao: RideDao,
    private val locationService: LocationService
) {
    companion object {
        private const val TAG = "RideRecorder"
        private const val MIN_DISTANCE_METERS = 20.0
        private const val HIGH_SPEED_MPS = 36.1f
    }

    private val recorderScope = CoroutineScope(Dispatchers.IO)

    private var recordingJob: Job? = null

    private var currentSessionId: String? = null

    private var lastLat = 0.0
    private var lastLng = 0.0
    private var totalDistanceMeters = 0f

    private val waypointBuffer = java.util.Collections.synchronizedList(mutableListOf<RawWaypointEntity>())
    private var lastFlushTime = System.currentTimeMillis()
    private val BUFFER_SIZE_LIMIT = 20
    private val FLUSH_INTERVAL_MS = 10_000L

    private val _lastSummary = MutableStateFlow<RideSummary?>(null)
    val lastSummary: StateFlow<RideSummary?> = _lastSummary

    fun startRecording(roomName: String) {
        if (recordingJob != null) {
            Log.w(TAG, "Already recording — ignoring startRecording()")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        lastLat = 0.0
        lastLng = 0.0
        totalDistanceMeters = 0f
        synchronized(waypointBuffer) { waypointBuffer.clear() }
        lastFlushTime = System.currentTimeMillis()

        recorderScope.launch {
            val session = RideSessionEntity(
                id        = sessionId,
                roomName  = roomName,
                startTime = System.currentTimeMillis()
            )
            rideDao.insertSession(session)
            Log.i(TAG, "✅ Ride recording started: $sessionId")
        }

        recordingJob = locationService.currentLocation
            .onEach { loc ->
                val sid = currentSessionId ?: return@onEach
                if (loc == null) return@onEach

                val isFirstPoint = (lastLat == 0.0 && lastLng == 0.0)
                val dist = if (isFirstPoint) 0.0 else haversineMeters(lastLat, lastLng, loc.latitude, loc.longitude)

                if (isFirstPoint || dist >= MIN_DISTANCE_METERS) {
                    lastLat = loc.latitude
                    lastLng = loc.longitude
                    if (!isFirstPoint) {
                        totalDistanceMeters += dist.toFloat().coerceAtMost(1_000f)
                    }

                    queueWaypoint(
                        RawWaypointEntity(
                            sessionId = sid,
                            lat       = loc.latitude,
                            lng       = loc.longitude,
                            speedMps  = loc.speed,
                            heading   = loc.bearing,
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    if (loc.speed > HIGH_SPEED_MPS) {
                        rideDao.insertEvent(
                            ConvoyEventEntity(
                                sessionId = sid,
                                eventType = "HIGH_SPEED_ZONE",
                                lat       = loc.latitude,
                                lng       = loc.longitude,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            .launchIn(recorderScope)
    }

    private suspend fun queueWaypoint(waypoint: RawWaypointEntity) {
        waypointBuffer.add(waypoint)
        val now = System.currentTimeMillis()
        if (waypointBuffer.size >= BUFFER_SIZE_LIMIT || (now - lastFlushTime) >= FLUSH_INTERVAL_MS) {
            flushWaypoints()
        }
    }

    private suspend fun flushWaypoints() {
        if (waypointBuffer.isEmpty()) return
        val toFlush: List<RawWaypointEntity>
        synchronized(waypointBuffer) {
            if (waypointBuffer.isEmpty()) return
            toFlush = ArrayList(waypointBuffer)
            waypointBuffer.clear()
        }
        try {
            rideDao.insertWaypoints(toFlush)
            lastFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush waypoint batch: ${e.message}")
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null

        val sid = currentSessionId ?: return
        currentSessionId = null

        recorderScope.launch {
            flushWaypoints()

            val existing = rideDao.getSessionById(sid) ?: return@launch
            val endTime = System.currentTimeMillis()
            rideDao.updateSession(
                existing.copy(
                    endTime              = endTime,
                    totalDistanceMeters  = totalDistanceMeters,
                    isSynced             = false
                )
            )

            val maxSpeedMps = rideDao.getMaxSpeed(sid) ?: 0f
            _lastSummary.value = RideSummary(
                sessionId       = sid,
                durationMinutes = (((endTime - existing.startTime) / 60000L).toInt()).coerceAtLeast(0),
                distanceKm      = totalDistanceMeters / 1000f,
                topSpeedKmh     = maxSpeedMps * 3.6f
            )

            Log.i(TAG, "Ride recording stopped: $sid (${totalDistanceMeters / 1000f} km)")
        }
    }

    fun discardLastSession() {
        val summary = _lastSummary.value ?: return
        _lastSummary.value = null
        recorderScope.launch {
            rideDao.deleteWaypointsForSession(summary.sessionId)
            rideDao.deleteSession(summary.sessionId)
            Log.i(TAG, "Discarded ride session: ${summary.sessionId}")
        }
    }

    fun clearSummary() {
        _lastSummary.value = null
    }

    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6_371_000.0
        val p = Math.PI / 180
        val a = 0.5 - Math.cos((lat2 - lat1) * p) / 2 +
                Math.cos(lat1 * p) * Math.cos(lat2 * p) *
                (1 - Math.cos((lon2 - lon1) * p)) / 2
        return 2 * R * Math.asin(Math.sqrt(a))
    }
}

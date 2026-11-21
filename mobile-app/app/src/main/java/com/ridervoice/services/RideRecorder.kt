package com.ridervoice.services

import android.util.Log
import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val topSpeedKmh: Float,
    val roomName: String = ""
)

@Singleton
class RideRecorder internal constructor(
    private val rideDao: RideDao,
    private val locationService: LocationService,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository? = null
) {
    @Inject
    constructor(
        rideDao: RideDao,
        locationService: LocationService,
        activeRideRepository: com.ridervoice.state.ActiveRideRepository
    ) : this(rideDao, locationService, Dispatchers.IO, activeRideRepository)

    companion object {
        private const val TAG = "RideRecorder"
        private const val MIN_DISTANCE_METERS = 20.0
        private const val HIGH_SPEED_MPS = 36.1f
        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        internal fun isUuidLike(value: String): Boolean = UUID_REGEX.matches(value.trim())
    }

    private val recorderScope = CoroutineScope(ioDispatcher + SupervisorJob())

    private var recordingJob: Job? = null

    private var currentSessionId: String? = null
    private var currentRoomKey: String? = null
    private var currentDisplayRoomName: String? = null

    private var lastLat = 0.0
    private var lastLng = 0.0
    private var totalDistanceMeters = 0f

    private val waypointBuffer = java.util.Collections.synchronizedList(mutableListOf<RawWaypointEntity>())
    private var lastFlushTime = System.currentTimeMillis()
    private val BUFFER_SIZE_LIMIT = 20
    private val FLUSH_INTERVAL_MS = 10_000L

    private val _lastSummary = MutableStateFlow<RideSummary?>(null)
    val lastSummary: StateFlow<RideSummary?> = _lastSummary

    private fun resolveDisplayRoomName(rawRoomKey: String): String {
        val trimmedRaw = rawRoomKey.trim()
        val active = activeRideRepository?.state?.value
        if (active != null && (active.roomId == trimmedRaw || trimmedRaw.isBlank())) {
            val activeName = active.displayName.trim()
            if (activeName.isNotBlank() && !isUuidLike(activeName)) {
                return activeName
            }
        }
        val ended = activeRideRepository?.lastEnded?.value
        if (ended != null) {
            val endedName = ended.convoyName.trim()
            if (endedName.isNotBlank() && !isUuidLike(endedName)) {
                return endedName
            }
        }
        return trimmedRaw.ifBlank { "Ride" }
    }

    fun startRecording(roomName: String) {
        val resolvedName = resolveDisplayRoomName(roomName)
        if (recordingJob != null && currentRoomKey == roomName) {
            val existingSid = currentSessionId
            val previousName = currentDisplayRoomName
            if (existingSid != null && resolvedName != previousName && !isUuidLike(resolvedName)) {
                currentDisplayRoomName = resolvedName
                recorderScope.launch {
                    val existing = rideDao.getSessionById(existingSid) ?: return@launch
                    if (existing.roomName.isBlank() || isUuidLike(existing.roomName)) {
                        rideDao.updateSession(existing.copy(roomName = resolvedName))
                    }
                }
            }
            Log.d(TAG, "Continuing existing ride session $currentSessionId across transport transition for room=$resolvedName")
            return
        }
        if (recordingJob != null && currentRoomKey != roomName) {
            stopRecording()
        }

        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        currentRoomKey = roomName
        currentDisplayRoomName = resolvedName
        lastLat = 0.0
        lastLng = 0.0
        totalDistanceMeters = 0f
        synchronized(waypointBuffer) { waypointBuffer.clear() }
        lastFlushTime = System.currentTimeMillis()

        recorderScope.launch {
            val session = RideSessionEntity(
                id        = sessionId,
                roomName  = resolvedName,
                startTime = System.currentTimeMillis()
            )
            rideDao.insertSession(session)
            Log.i(TAG, "✅ Ride recording started: $sessionId ($resolvedName)")
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
        val rawKey = currentRoomKey
        currentSessionId = null
        currentRoomKey = null
        currentDisplayRoomName = null

        recorderScope.launch {
            flushWaypoints()

            val existing = rideDao.getSessionById(sid) ?: return@launch
            val endTime = System.currentTimeMillis()
            val finalRoomName = if (existing.roomName.isBlank() || isUuidLike(existing.roomName)) {
                resolveDisplayRoomName(rawKey ?: existing.roomName)
            } else {
                existing.roomName
            }
            rideDao.updateSession(
                existing.copy(
                    roomName             = finalRoomName,
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
                topSpeedKmh     = maxSpeedMps * 3.6f,
                roomName        = finalRoomName
            )

            Log.i(TAG, "Ride recording stopped: $sid ($finalRoomName, ${totalDistanceMeters / 1000f} km)")
        }
    }

    fun saveSessionTitle(title: String?, fallbackRoomName: String? = null) {
        val summary = _lastSummary.value
        val sessionId = summary?.sessionId
        if (sessionId == null) {
            _lastSummary.value = null
            return
        }
        val trimmedTitle = title?.trim().orEmpty()
        val trimmedFallback = fallbackRoomName?.trim().orEmpty()
        recorderScope.launch {
            try {
                val existing = rideDao.getSessionById(sessionId)
                if (existing != null) {
                    val resolvedName = when {
                        trimmedTitle.isNotBlank() -> trimmedTitle
                        trimmedFallback.isNotBlank() && !isUuidLike(trimmedFallback) -> trimmedFallback
                        existing.roomName.isNotBlank() && !isUuidLike(existing.roomName) -> existing.roomName
                        trimmedFallback.isNotBlank() -> trimmedFallback
                        else -> existing.roomName
                    }
                    if (resolvedName.isNotBlank() && resolvedName != existing.roomName) {
                        rideDao.updateSession(existing.copy(roomName = resolvedName, isSynced = false))
                        Log.i(TAG, "Saved ride session title for $sessionId: $resolvedName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update ride session title: ${e.message}")
            } finally {
                _lastSummary.value = null
            }
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

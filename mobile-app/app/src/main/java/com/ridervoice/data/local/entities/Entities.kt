package com.ridervoice.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ride_sessions")
data class RideSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val totalDistanceMeters: Float = 0f,
    val isSynced: Boolean = false
)

@Entity(tableName = "raw_waypoints")
data class RawWaypointEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val lat: Double,
    val lng: Double,
    val speedMps: Float,
    val heading: Float,
    val timestamp: Long
)

@Entity(tableName = "convoy_events")
data class ConvoyEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val eventType: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long
)

package com.ridervoice.data.local

import androidx.room.*
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: RideSessionEntity)

    @Update
    suspend fun updateSession(session: RideSessionEntity)

    @Insert
    suspend fun insertWaypoint(waypoint: RawWaypointEntity)

    @Insert
    suspend fun insertEvent(event: ConvoyEventEntity)

    // BUG FIX: RideRecorder.stopRecording() needs to fetch the session before
    // updating it (Room requires the full entity for @Update). This query was missing.
    @Query("SELECT * FROM ride_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): RideSessionEntity?

    @Query("SELECT * FROM ride_sessions WHERE isSynced = 0")
    suspend fun getUnsyncedSessions(): List<RideSessionEntity>

    @Query("SELECT * FROM raw_waypoints WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getWaypointsForSession(sessionId: String): List<RawWaypointEntity>

    @Query("SELECT * FROM convoy_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsForSession(sessionId: String): List<ConvoyEventEntity>

    @Query("SELECT * FROM ride_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<RideSessionEntity>>

    @Query("DELETE FROM raw_waypoints WHERE sessionId = :sessionId")
    suspend fun deleteWaypointsForSession(sessionId: String)

    @Query("SELECT MAX(speedMps) FROM raw_waypoints WHERE sessionId = :sessionId")
    suspend fun getMaxSpeed(sessionId: String): Float?

    @Query("SELECT MAX(speedMps) FROM raw_waypoints")
    suspend fun getAllTimeMaxSpeed(): Float?

    @Query("DELETE FROM ride_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Database(
    entities  = [RideSessionEntity::class, RawWaypointEntity::class, ConvoyEventEntity::class],
    version   = 1,
    exportSchema = false
)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}

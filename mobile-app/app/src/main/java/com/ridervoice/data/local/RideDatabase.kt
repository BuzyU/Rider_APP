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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoints(waypoints: List<RawWaypointEntity>)

    @Insert
    suspend fun insertEvent(event: ConvoyEventEntity)

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
    version   = 2,
    exportSchema = false
)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        /** Non-destructive migration from v1 to v2 ensuring indices and tables exist cleanly. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ride_sessions` (`id` TEXT NOT NULL, `roomName` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `totalDistanceMeters` REAL NOT NULL, `isSynced` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `raw_waypoints` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `lat` DOUBLE NOT NULL, `lng` DOUBLE NOT NULL, `speedMps` REAL NOT NULL, `heading` REAL NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_waypoints_sessionId` ON `raw_waypoints` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_waypoints_timestamp` ON `raw_waypoints` (`timestamp`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `convoy_events` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `eventType` TEXT NOT NULL, `lat` DOUBLE NOT NULL, `lng` DOUBLE NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_convoy_events_sessionId` ON `convoy_events` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_convoy_events_timestamp` ON `convoy_events` (`timestamp`)")
            }
        }
    }
}

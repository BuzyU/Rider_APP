package com.ridervoice.diagnostics.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ridervoice.diagnostics.data.entities.DiagOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outbox: DiagOutboxEntity)

    @Update
    suspend fun update(outbox: DiagOutboxEntity)

    @Query("SELECT * FROM diag_outbox ORDER BY seq ASC, id ASC LIMIT 1")
    suspend fun getNextPending(): DiagOutboxEntity?

    @Query("SELECT * FROM diag_outbox WHERE localSessionId = :localSessionId ORDER BY seq ASC, id ASC")
    suspend fun getPendingForSession(localSessionId: String): List<DiagOutboxEntity>

    @Query("DELETE FROM diag_outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM diag_outbox")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM diag_outbox")
    suspend fun getPendingCount(): Int

    @Query("DELETE FROM diag_outbox WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOld(cutoffTimestamp: Long): Int

    @Query("SELECT MAX(seq) FROM diag_outbox WHERE localSessionId = :localSessionId")
    suspend fun getMaxSeq(localSessionId: String): Int?

    @Query("SELECT MAX(createdAt) FROM diag_outbox")
    fun getLatestQueuedTime(): Flow<Long?>
}

package com.ridervoice.diagnostics.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ridervoice.diagnostics.data.entities.DiagSessionEntity

@Dao
interface DiagSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: DiagSessionEntity)

    @Update
    suspend fun update(session: DiagSessionEntity)

    @Query("SELECT * FROM diag_session WHERE state = 'ACTIVE' LIMIT 1")
    suspend fun getActiveSession(): DiagSessionEntity?

    @Query("SELECT * FROM diag_session WHERE localSessionId = :localSessionId LIMIT 1")
    suspend fun getSession(localSessionId: String): DiagSessionEntity?

    @Query("SELECT * FROM diag_session WHERE state = 'ACTIVE'")
    suspend fun getOrphanedSessions(): List<DiagSessionEntity>

    @Query("UPDATE diag_session SET runId = :runId WHERE localSessionId = :localSessionId")
    suspend fun updateRunId(localSessionId: String, runId: String)

    @Query("UPDATE diag_session SET lastSeq = :lastSeq WHERE localSessionId = :localSessionId")
    suspend fun updateLastSeq(localSessionId: String, lastSeq: Int)

    @Query("UPDATE diag_session SET state = :state WHERE localSessionId = :localSessionId")
    suspend fun updateState(localSessionId: String, state: String)
}

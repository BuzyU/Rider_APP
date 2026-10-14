package com.ridervoice.diagnostics.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ridervoice.diagnostics.data.entities.DiagSampleEntity

@Dao
interface DiagSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: DiagSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<DiagSampleEntity>)

    @Query("SELECT * FROM diag_sample WHERE localSessionId = :localSessionId ORDER BY id ASC LIMIT :limit")
    suspend fun getSamplesForSession(localSessionId: String, limit: Int = 200): List<DiagSampleEntity>

    @Query("DELETE FROM diag_sample WHERE id IN (:ids)")
    suspend fun deleteSamples(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM diag_sample WHERE localSessionId = :localSessionId")
    suspend fun getSampleCount(localSessionId: String): Int

    @Query("DELETE FROM diag_sample WHERE localSessionId = :localSessionId")
    suspend fun deleteAllForSession(localSessionId: String)
}

package com.ridervoice.diagnostics.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diag_session")
data class DiagSessionEntity(
    @PrimaryKey
    val localSessionId: String,
    val convoyId: String,
    val runId: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val state: String = "ACTIVE", // ACTIVE, STOPPED, FINALIZED
    val lastSeq: Int = 0
)

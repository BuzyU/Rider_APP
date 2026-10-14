package com.ridervoice.diagnostics.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diag_outbox")
data class DiagOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localSessionId: String,
    val seq: Int,
    val type: String, // BATCH, STOP
    val payloadJson: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

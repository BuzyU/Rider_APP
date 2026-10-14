package com.ridervoice.diagnostics.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diag_sample")
data class DiagSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localSessionId: String,
    val type: String, // SELF, INBOUND, EVENT
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

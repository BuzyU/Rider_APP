package com.ridervoice.diagnostics.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ridervoice.diagnostics.data.dao.DiagOutboxDao
import com.ridervoice.diagnostics.data.dao.DiagSampleDao
import com.ridervoice.diagnostics.data.dao.DiagSessionDao
import com.ridervoice.diagnostics.data.entities.DiagOutboxEntity
import com.ridervoice.diagnostics.data.entities.DiagSampleEntity
import com.ridervoice.diagnostics.data.entities.DiagSessionEntity

@Database(
    entities = [
        DiagSessionEntity::class,
        DiagSampleEntity::class,
        DiagOutboxEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun sessionDao(): DiagSessionDao
    abstract fun sampleDao(): DiagSampleDao
    abstract fun outboxDao(): DiagOutboxDao
}

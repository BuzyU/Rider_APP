package com.ridervoice.diagnostics.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.ridervoice.diagnostics.data.DiagnosticsDatabase
import androidx.room.withTransaction
import com.ridervoice.diagnostics.data.entities.DiagOutboxEntity
import com.ridervoice.diagnostics.data.entities.DiagSessionEntity
import com.ridervoice.diagnostics.models.DiagnosticBatchPayload
import com.ridervoice.diagnostics.models.DiagnosticBatchRequest
import com.ridervoice.diagnostics.models.DiagnosticJoinRequest
import com.ridervoice.diagnostics.models.DiagnosticStopRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticUploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DiagnosticsDatabase,
    private val apiService: DiagnosticApiService,
    private val gson: Gson
) {

    companion object {
        private const val TAG = "DiagUploadManager"
        const val UNIQUE_WORK_NAME = "DiagnosticUploadWork"
        private const val MAX_RETENTION_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _statusText = MutableStateFlow("Idle")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private var droppedRowCount = 0

    init {
        scope.launch {
            database.outboxDao().getPendingCountFlow().collect { count ->
                _queuedCount.value = count
                updateStatusLine()
            }
        }
    }

    /**
     * Enqueues a batch of telemetry samples in one atomic transaction,
     * assigning a monotonic sequence number per session, and purges the packaged samples.
     */
    suspend fun enqueueBatch(localSessionId: String, sampleIds: List<Long>, payload: DiagnosticBatchPayload): Int {
        val session = database.sessionDao().getSession(localSessionId) ?: return 0
        val nextSeq = session.lastSeq + 1

        val outboxItem = DiagOutboxEntity(
            localSessionId = localSessionId,
            seq = nextSeq,
            type = "BATCH",
            payloadJson = gson.toJson(payload)
        )

        database.withTransaction {
            database.sessionDao().updateLastSeq(localSessionId, nextSeq)
            database.outboxDao().insert(outboxItem)
            database.sampleDao().deleteSamples(sampleIds)
        }

        triggerUpload()
        return nextSeq
    }

    /**
     * Enqueues STOP request with lastSeq AFTER all batches for that session.
     */
    suspend fun enqueueStop(localSessionId: String) {
        val session = database.sessionDao().getSession(localSessionId) ?: return
        val lastSeq = session.lastSeq

        val stopItem = DiagOutboxEntity(
            localSessionId = localSessionId,
            seq = lastSeq,
            type = "STOP",
            payloadJson = "{}"
        )

        database.withTransaction {
            database.sessionDao().updateState(localSessionId, "STOPPED")
            database.outboxDao().insert(stopItem)
        }

        triggerUpload()
    }

    /**
     * Schedules WorkManager work and attempts immediate in-process upload.
     */
    fun triggerUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DiagnosticUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        // Immediate opportunistic upload attempt
        scope.launch {
            processOutbox()
        }
    }

    /**
     * Processes outbox rows strictly ordered by (seq ASC, id ASC).
     */
    suspend fun processOutbox(): androidx.work.ListenableWorker.Result {
        // 1. Drop rows older than 7 days
        val cutoff = System.currentTimeMillis() - MAX_RETENTION_MS
        val dropped = database.outboxDao().deleteOld(cutoff)
        if (dropped > 0) {
            droppedRowCount += dropped
            Log.w(TAG, "Dropped $dropped diagnostic outbox rows older than 7 days")
            updateStatusLine()
        }

        var item = database.outboxDao().getNextPending()
        while (item != null) {
            val session = database.sessionDao().getSession(item.localSessionId)
            if (session == null) {
                // Orphaned row without session; remove
                database.outboxDao().delete(item.id)
                item = database.outboxDao().getNextPending()
                continue
            }

            // Ensure runId is known before uploading batches or stop
            var runId = session.runId
            if (runId.isNullOrEmpty()) {
                val joinSuccess = ensureRunId(session)
                if (!joinSuccess) {
                    _statusText.value = "Offline: waiting to connect run"
                    return androidx.work.ListenableWorker.Result.retry()
                }
                val refreshedSession = database.sessionDao().getSession(item.localSessionId)
                runId = refreshedSession?.runId
                if (runId.isNullOrEmpty()) {
                    return androidx.work.ListenableWorker.Result.retry()
                }
            }

            val success = if (item.type == "BATCH") {
                uploadBatch(item, runId)
            } else {
                uploadStop(item, runId)
            }

            if (!success) {
                return androidx.work.ListenableWorker.Result.retry()
            }

            // Next row
            item = database.outboxDao().getNextPending()
        }

        return androidx.work.ListenableWorker.Result.success()
    }

    private suspend fun ensureRunId(session: DiagSessionEntity): Boolean {
        try {
            val response = apiService.joinDiagnosticRun(DiagnosticJoinRequest(convoyId = session.convoyId))
            if (response.isSuccessful && response.body() != null) {
                val runId = response.body()!!.runId
                database.sessionDao().updateRunId(session.localSessionId, runId)
                Log.i(TAG, "Obtained runId $runId for session ${session.localSessionId}")
                return true
            } else if (response.code() == 401) {
                _statusText.value = "Upload paused: authenticating"
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to join diagnostic run: ${e.message}")
        }
        return false
    }

    private suspend fun uploadBatch(item: DiagOutboxEntity, runId: String): Boolean {
        return try {
            val payload = gson.fromJson(item.payloadJson, DiagnosticBatchPayload::class.java)
            val request = DiagnosticBatchRequest(
                runId = runId,
                sequenceNumber = item.seq,
                payload = payload
            )

            val response = apiService.postDiagnosticBatch(request)
            if (response.isSuccessful) {
                database.outboxDao().delete(item.id)
                recordSuccess()
                true
            } else if (response.code() == 401) {
                _statusText.value = "Upload paused: authenticating"
                false
            } else if (response.code() == 413 || response.code() == 400) {
                // Unrecoverable payload violation; remove from outbox to prevent blocking
                Log.e(TAG, "Unrecoverable batch error ${response.code()}: ${response.errorBody()?.string()}")
                database.outboxDao().delete(item.id)
                true
            } else {
                database.outboxDao().update(item.copy(attempts = item.attempts + 1, lastError = "HTTP ${response.code()}"))
                false
            }
        } catch (e: IOException) {
            database.outboxDao().update(item.copy(attempts = item.attempts + 1, lastError = e.message))
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading batch: ${e.message}")
            false
        }
    }

    private suspend fun uploadStop(item: DiagOutboxEntity, runId: String): Boolean {
        return try {
            val request = DiagnosticStopRequest(
                runId = runId,
                lastSeq = item.seq
            )

            val response = apiService.stopDiagnosticRun(request)
            if (response.isSuccessful) {
                database.withTransaction {
                    database.outboxDao().delete(item.id)
                    database.sessionDao().updateState(item.localSessionId, "FINALIZED")
                }
                recordSuccess()
                true
            } else if (response.code() == 401) {
                _statusText.value = "Upload paused: authenticating"
                false
            } else {
                database.outboxDao().update(item.copy(attempts = item.attempts + 1, lastError = "HTTP ${response.code()}"))
                false
            }
        } catch (e: Exception) {
            database.outboxDao().update(item.copy(attempts = item.attempts + 1, lastError = e.message))
            false
        }
    }

    private fun recordSuccess() {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _statusText.value = "Last upload: $timeStr"
        updateStatusLine()
    }

    private fun updateStatusLine() {
        val count = _queuedCount.value
        val droppedNotice = if (droppedRowCount > 0) " (${droppedRowCount} expired dropped)" else ""
        if (count > 0) {
            _statusText.value = "$count queued$droppedNotice"
        }
    }
}

package com.ridervoice.diagnostics.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DiagnosticUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadManager: DiagnosticUploadManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return uploadManager.processOutbox()
    }
}

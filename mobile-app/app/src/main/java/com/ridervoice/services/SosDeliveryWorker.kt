package com.ridervoice.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ridervoice.network.ApiService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Delivers queued SOS alerts once the network is back. Idempotent server side via clientAlertId. */
@HiltWorker
class SosDeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: ApiService,
    private val outbox: PersistentSosOutbox
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var needRetry = false
        for (req in outbox.pending()) {
            val outcome = try {
                val res = api.sendSosAlert(req)
                SosDeliveryPolicy.classify(
                    res.code(),
                    delivered = res.body()?.let { it.sent || it.alertId != null } == true
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                SosOutcome.RETRY
            }
            when (outcome) {
                SosOutcome.DELIVERED, SosOutcome.DROP -> outbox.remove(req.clientAlertId)
                SosOutcome.RETRY -> needRetry = true
            }
        }
        return if (needRetry) Result.retry() else Result.success()
    }
}


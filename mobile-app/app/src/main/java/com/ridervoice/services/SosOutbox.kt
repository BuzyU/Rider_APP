package com.ridervoice.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ridervoice.models.SosAlertRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What to do with a queued SOS after a delivery attempt. */
enum class SosOutcome { DELIVERED, DROP, RETRY }

/**
 * Pure retry policy. Network errors, 5xx and 429 are retried. Other 4xx will never succeed on retry
 * (403 not a member, 410 ended, 400 bad request) so they are dropped instead of looping forever.
 */
object SosDeliveryPolicy {
    fun classify(httpCode: Int?, delivered: Boolean = false): SosOutcome = when {
        httpCode == null -> SosOutcome.RETRY
        httpCode in 200..299 -> if (delivered) SosOutcome.DELIVERED else SosOutcome.RETRY
        httpCode == 429 || httpCode >= 500 -> SosOutcome.RETRY
        httpCode in 400..499 -> SosOutcome.DROP
        else -> SosOutcome.RETRY
    }
}

/** Durable queue of SOS requests that could not be delivered yet. */
interface SosOutbox {
    fun enqueue(req: SosAlertRequest)
    fun pending(): List<SosAlertRequest>
    fun remove(clientAlertId: String)
    fun scheduleFlush()
}

class InMemorySosOutbox : SosOutbox {
    private val items = java.util.Collections.synchronizedList(mutableListOf<SosAlertRequest>())
    override fun enqueue(req: SosAlertRequest) {
        synchronized(items) { if (items.none { it.clientAlertId == req.clientAlertId }) items.add(req) }
    }
    override fun pending(): List<SosAlertRequest> = synchronized(items) { items.toList() }
    override fun remove(clientAlertId: String) { synchronized(items) { items.removeAll { it.clientAlertId == clientAlertId } } }
    override fun scheduleFlush() {}
}

/** SharedPreferences-backed outbox: survives process death. Delivery is done by [SosDeliveryWorker]. */
@Singleton
class PersistentSosOutbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : SosOutbox {

    companion object {
        const val WORK_NAME = "SosDeliveryWork"
        private const val PREFS = "sos_outbox"
        private const val KEY = "items"
        private const val MAX_ITEMS = 20
        private const val MAX_AGE_MS = 6L * 60 * 60 * 1000 // an SOS older than 6 h is stale
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val type = object : TypeToken<List<SosAlertRequest>>() {}.type
    private val lock = Any()

    private fun load(): MutableList<SosAlertRequest> = try {
        gson.fromJson<List<SosAlertRequest>>(prefs.getString(KEY, null) ?: "[]", type)
            ?.toMutableList() ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    private fun save(list: List<SosAlertRequest>) { prefs.edit().putString(KEY, gson.toJson(list)).apply() }

    override fun enqueue(req: SosAlertRequest) = synchronized(lock) {
        val list = load()
        if (list.none { it.clientAlertId == req.clientAlertId }) list.add(req)
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(list)
    }

    override fun pending(): List<SosAlertRequest> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = load()
        val fresh = list.filter { now - it.createdAtMs <= MAX_AGE_MS }
        if (fresh.size != list.size) save(fresh)
        fresh
    }

    override fun remove(clientAlertId: String) = synchronized(lock) {
        save(load().filterNot { it.clientAlertId == clientAlertId })
    }

    override fun scheduleFlush() {
        try {
            val req = OneTimeWorkRequestBuilder<SosDeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        } catch (e: Exception) {
            android.util.Log.w("SosOutbox", "scheduleFlush failed: ${e.javaClass.simpleName}")
        }
    }
}


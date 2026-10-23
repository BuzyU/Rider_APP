package com.ridervoice.diagnostics.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.ridervoice.diagnostics.data.DiagnosticsDatabase
import com.ridervoice.diagnostics.data.entities.DiagSampleEntity
import com.ridervoice.diagnostics.data.entities.DiagSessionEntity
import com.ridervoice.diagnostics.models.DiagnosticBatchPayload
import com.ridervoice.diagnostics.models.EventSample
import com.ridervoice.diagnostics.models.InboundMetricSample
import com.ridervoice.diagnostics.models.SelfMetricSample
import com.ridervoice.diagnostics.upload.DiagnosticUploadManager
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkResilienceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkDiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DiagnosticsDatabase,
    private val liveKitManager: LiveKitManager,
    private val networkResilienceManager: NetworkResilienceManager,
    private val uploadManager: DiagnosticUploadManager,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "NetDiagCollector"
        private const val SAMPLE_INTERVAL_MS = 2000L
        private const val FLUSH_INTERVAL_MS = 30000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val flushMutex = Mutex()
    private val deltaLossCalculator = DeltaLossCalculator()

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _latestSelfMetric = MutableStateFlow<SelfMetricSample?>(null)
    val latestSelfMetric: StateFlow<SelfMetricSample?> = _latestSelfMetric.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private var activeSession: DiagSessionEntity? = null
    private var sampleJob: Job? = null
    private var flushJob: Job? = null
    private var eventJobs = mutableListOf<Job>()

    /**
     * Starts collecting network diagnostics for the active convoy ride.
     */
    @Synchronized
    fun startSession(convoyId: String) {
        if (_isCollecting.value) {
            Log.d(TAG, "Diagnostic session already collecting for ${activeSession?.convoyId}")
            return
        }

        val localSessionId = UUID.randomUUID().toString()
        val session = DiagSessionEntity(
            localSessionId = localSessionId,
            convoyId = convoyId,
            startedAt = System.currentTimeMillis(),
            state = "ACTIVE"
        )
        activeSession = session
        deltaLossCalculator.clear()
        _isCollecting.value = true

        scope.launch(Dispatchers.IO) {
            database.sessionDao().insert(session)
            recordEvent("SESSION_STARTED", "convoyId=$convoyId, localSessionId=$localSessionId")
        }

        // 1. Polling loop every 2s
        sampleJob = scope.launch {
            Log.i(TAG, ">>> Network Diagnostics Collector ACTIVE for convoy: $convoyId <<<")
            while (isActive) {
                try {
                    collectMetricsSample(session)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed collecting metrics sample: ${e.message}")
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        // 2. Batch flusher loop every 30s
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                try {
                    flushBatch(session)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed flushing periodic batch: ${e.message}")
                }
            }
        }

        liveKitManager.onP2pPingResult = { peer, rttMs ->
            recordEvent("P2P_PING", "peer=$peer, rttMs=$rttMs")
        }

        // 3. Observe event transitions
        startEventObservers()
    }

    /**
     * Stops collecting, performs final flush, and enqueues STOP with lastSeq.
     */
    @Synchronized
    fun stopSession() {
        val session = activeSession ?: return
        Log.i(TAG, "Stopping diagnostic session ${session.localSessionId}")

        liveKitManager.onP2pPingResult = null
        _isCollecting.value = false
        sampleJob?.cancel()
        sampleJob = null
        flushJob?.cancel()
        flushJob = null
        eventJobs.forEach { it.cancel() }
        eventJobs.clear()
        activeSession = null

        scope.launch(Dispatchers.IO) {
            recordEvent("SESSION_STOPPING", "localSessionId=${session.localSessionId}")

            // Flush all pending samples in batches <= 200
            flushMutex.withLock {
                var pendingCount = database.sampleDao().getSampleCount(session.localSessionId)
                while (pendingCount > 0) {
                    val flushed = flushBatchInternal(session)
                    if (!flushed) break
                    pendingCount = database.sampleDao().getSampleCount(session.localSessionId)
                }

                // Strictly enqueue STOP after all batches
                uploadManager.enqueueStop(session.localSessionId)
                Log.i(TAG, "Diagnostic session ${session.localSessionId} stopped and STOP enqueued")
            }
        }
    }

    private var lastBytesSent = 0L
    private var lastBytesRecv = 0L
    private var lastBitrateTimeMs = 0L
    private val lastEventTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun collectMetricsSample(session: DiagSessionEntity) {
        val snapshot = liveKitManager.collectStatsSnapshot()
        val nowMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        val nowSec = nowMs / 1000L

        // Device Metrics
        val netType = getNetworkType()
        val battery = getBatteryLevel()
        val thermal = getThermalStatus()
        val isFg = isAppForeground()

        val currSent = snapshot?.bytesSent ?: 0L
        val currRecv = snapshot?.bytesReceived ?: 0L
        val dtSec = if (lastBitrateTimeMs > 0L) (nowMs - lastBitrateTimeMs) / 1000.0 else 0.0
        val computedBitrate = if (dtSec > 0.0 && lastBitrateTimeMs > 0L) {
            val deltaBytes = (currSent - lastBytesSent).coerceAtLeast(0L) + (currRecv - lastBytesRecv).coerceAtLeast(0L)
            (deltaBytes * 8.0) / (dtSec * 1000.0)
        } else null
        lastBitrateTimeMs = nowMs
        lastBytesSent = currSent
        lastBytesRecv = currRecv

        // 1. SELF Metric Sample
        val self = SelfMetricSample(
            timestamp = nowSec,
            deviceEpochMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            rttMs = snapshot?.rttMs,
            packetsSent = snapshot?.packetsSent ?: 0L,
            packetsReceived = snapshot?.packetsReceived ?: 0L,
            packetsLost = snapshot?.packetsLost ?: 0L,
            bytesSent = currSent,
            bytesReceived = currRecv,
            bitrateKbps = computedBitrate,
            jitterMs = snapshot?.jitterMs,
            networkType = netType,
            batteryLevel = battery,
            thermalStatus = thermal,
            isForeground = isFg
        )

        _latestSelfMetric.value = self
        _sampleCount.value += 1

        val selfJson = gson.toJson(self)
        database.sampleDao().insert(
            DiagSampleEntity(
                localSessionId = session.localSessionId,
                type = "SELF",
                payloadJson = selfJson
            )
        )

        // Debug-only log
        if (Log.isLoggable("NetDiag", Log.DEBUG) || com.ridervoice.BuildConfig.DEBUG) {
            val rttStr = self.rttMs?.let { String.format(java.util.Locale.US, "%.1f ms", it) } ?: "N/A"
            Log.d(TAG, "[DiagSample] RTT: $rttStr | Sent: ${self.packetsSent} | Recv: ${self.packetsReceived} | Net: $netType | Bat: ${battery?.toInt()}% | Fg: $isFg")
        }

        // 2. INBOUND Metric Samples per remote rider
        if (snapshot != null) {
            for (inbound in snapshot.inboundRiders) {
                val trackKey = "${inbound.riderIdentity}_${inbound.trackSid ?: "audio"}"
                val intervalLoss = deltaLossCalculator.calculateIntervalLoss(
                    trackKey,
                    inbound.packetsReceived,
                    inbound.packetsLost
                )

                val inboundSample = InboundMetricSample(
                    timestamp = nowSec,
                    deviceEpochMs = nowMs,
                    elapsedRealtimeMs = elapsedMs,
                    remoteRider = inbound.riderIdentity,
                    trackSid = inbound.trackSid,
                    packetsReceived = inbound.packetsReceived,
                    packetsLost = inbound.packetsLost,
                    intervalLossPct = intervalLoss,
                    jitterMs = inbound.jitterMs,
                    jitterBufferDelay = inbound.jitterBufferDelay,
                    concealedSamples = inbound.concealedSamples,
                    concealmentEvents = null,
                    audioLevel = null,
                    bytesReceived = inbound.bytesReceived
                )

                database.sampleDao().insert(
                    DiagSampleEntity(
                        localSessionId = session.localSessionId,
                        type = "INBOUND",
                        payloadJson = gson.toJson(inboundSample)
                    )
                )
            }
        }
    }

    private suspend fun flushBatch(session: DiagSessionEntity) {
        flushMutex.withLock {
            flushBatchInternal(session)
        }
    }

    private suspend fun flushBatchInternal(session: DiagSessionEntity): Boolean {
        val samples = database.sampleDao().getSamplesForSession(session.localSessionId, limit = 200)
        if (samples.isEmpty()) return false

        val selfMetrics = mutableListOf<SelfMetricSample>()
        val inboundMetrics = mutableListOf<InboundMetricSample>()
        val events = mutableListOf<EventSample>()
        val sampleIds = mutableListOf<Long>()

        for (sample in samples) {
            sampleIds.add(sample.id)
            try {
                when (sample.type) {
                    "SELF" -> selfMetrics.add(gson.fromJson(sample.payloadJson, SelfMetricSample::class.java))
                    "INBOUND" -> inboundMetrics.add(gson.fromJson(sample.payloadJson, InboundMetricSample::class.java))
                    "EVENT" -> events.add(gson.fromJson(sample.payloadJson, EventSample::class.java))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error deserializing sample ${sample.id}: ${e.message}")
            }
        }

        val payload = DiagnosticBatchPayload(
            selfMetrics = selfMetrics,
            inboundMetrics = inboundMetrics,
            events = events
        )

        uploadManager.enqueueBatch(session.localSessionId, sampleIds, payload)
        return true
    }

    fun recordEvent(eventType: String, details: String?) {
        val session = activeSession ?: return
        val nowMs = System.currentTimeMillis()
        val lastMs = lastEventTimestamps[eventType] ?: 0L
        if (nowMs - lastMs < 1000L) {
            return
        }
        lastEventTimestamps[eventType] = nowMs

        val event = EventSample(
            timestamp = nowMs / 1000L,
            deviceEpochMs = nowMs,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            eventType = eventType,
            details = details
        )

        scope.launch(Dispatchers.IO) {
            try {
                database.sampleDao().insert(
                    DiagSampleEntity(
                        localSessionId = session.localSessionId,
                        type = "EVENT",
                        payloadJson = gson.toJson(event)
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed inserting event $eventType: ${e.message}")
            }
        }
    }

    private fun startEventObservers() {
        eventJobs.forEach { it.cancel() }
        eventJobs.clear()

        // 1. Network resilience health
        eventJobs.add(scope.launch {
            networkResilienceManager.networkHealth.collectLatest { health ->
                recordEvent("NETWORK_HEALTH_CHANGED", health.name)
            }
        })

        // 2. LiveKit Connection State
        eventJobs.add(scope.launch {
            liveKitManager.connectionState.collectLatest { state ->
                recordEvent("LIVEKIT_CONNECTION_STATE", state.name)
            }
        })

        // 3. User Mute
        eventJobs.add(scope.launch {
            liveKitManager.isUserMuted.collectLatest { muted ->
                recordEvent("USER_MUTE_TOGGLED", "isUserMuted=$muted")
            }
        })

        // 4. Mic Transmit
        eventJobs.add(scope.launch {
            liveKitManager.isMicEnabled.collectLatest { enabled ->
                recordEvent("MIC_TRANSMIT_STATE", "isMicEnabled=$enabled")
            }
        })
    }

    private fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork ?: return "NONE"
            val caps = cm.getNetworkCapabilities(active) ?: return "UNKNOWN"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                else -> "OTHER"
            }
        } catch (_: Exception) { "UNKNOWN" }
    }

    private fun getBatteryLevel(): Float? {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level / scale.toFloat()) * 100f else null
        } catch (_: Exception) { null }
    }

    private fun getThermalStatus(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (powerManager?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun isAppForeground(): Boolean {
        return try {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (_: Exception) { true }
    }
}

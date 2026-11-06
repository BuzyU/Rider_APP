package com.ridervoice.mesh.switching

import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.NetworkObserver
import com.ridervoice.utils.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Stage 3 Health Signals Provider.
 *
 * Samples local connectivity signals:
 * - validated Internet capability (via NetworkObserver / ValidatedInternetSource)
 * - LiveKit connection state & reconnect attempts
 * - Active 3s HEAD health probe to backend (throttled to at most once per 5s on IO dispatcher)
 * - Mesh routing state (via MeshControl)
 *
 * Drives AutomaticSwitchStateMachine once per second while active.
 */
class LocalHealthSignalsProvider(
    private val networkObserver: NetworkObserver? = null,
    private val liveKitConnectionState: StateFlow<ConnectionState>? = null,
    private val liveKitReconnectAttempts: () -> Int = { 0 },
    private val meshControl: MeshControl? = null,
    private val healthProbe: (suspend () -> Boolean)? = null,
    private val isInternetValidated: (() -> Boolean)? = null,
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val validatedSource: ValidatedInternetSource? = null
) {
    private val probeDispatcher: CoroutineDispatcher = dispatcher
    private val watcherScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var lastInterfaceChangeMs: Long = 0L
    private var watcherJob: kotlinx.coroutines.Job? = null

    fun startWatching() {
        if (watcherJob?.isActive == true) return
        watcherJob = watcherScope.launch {
            validatedSource?.validatedFlow?.collect {
                lastInterfaceChangeMs = clock.nowMs()
            }
        }
    }

    fun stopWatching() {
        watcherJob?.cancel()
        watcherJob = null
    }

    fun close() {
        stopWatching()
        watcherScope.cancel()
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private var lastProbeTimeMs: Long = 0L
    private var lastProbeResult: Boolean = true

    suspend fun sampleSignals(): LocalHealthSignals {
        val validatedInternet = isInternetValidated?.invoke() ?: false

        val lkState = liveKitConnectionState?.value ?: ConnectionState.DISCONNECTED
        val isLkConnected = lkState == ConnectionState.CONNECTED
        val isLkReconnecting = lkState == ConnectionState.RECONNECTING
        val reconnectAttempts = liveKitReconnectAttempts()

        val now = clock.nowMs()

        // Rule 5:
        // - Probe on probeDispatcher (Dispatchers.IO) only.
        // - Probe at most once per 5000 ms.
        // - Do not probe when internet + LiveKit are healthy; stop probing when recovered.
        val probeOk: Boolean = if (validatedInternet && isLkConnected) {
            lastProbeResult = true
            true
        } else {
            if (now - lastProbeTimeMs >= 5_000L || lastProbeTimeMs == 0L) {
                lastProbeTimeMs = now
                val result = withContext(probeDispatcher) {
                    healthProbe?.invoke() ?: executeActiveProbe()
                }
                lastProbeResult = result
                result
            } else {
                lastProbeResult
            }
        }

        val validMeshRoute = meshControl?.hasValidRoute?.value ?: false
        val isMeshAlive = meshControl?.isLinkAlive?.value ?: true

        return LocalHealthSignals(
            isValidatedInternet = validatedInternet,
            isLiveKitConnected = isLkConnected,
            isLiveKitReconnecting = isLkReconnecting,
            liveKitReconnectAttempts = reconnectAttempts,
            isHealthProbeSuccessful = probeOk,
            hasValidMeshRoute = validMeshRoute,
            isMeshTransportAlive = isMeshAlive,
            offlineAvailable = true,
            lastInterfaceChangeMs = lastInterfaceChangeMs
        )
    }

    private fun executeActiveProbe(): Boolean = runCatching {
        val req = Request.Builder()
            .url(buildHealthUrl(Constants.BASE_URL))
            .head()
            .build()
        probeClient.newCall(req).execute().use { response ->
            response.isSuccessful
        }
    }.getOrDefault(false)

    companion object {
        fun buildHealthUrl(baseUrl: String = Constants.BASE_URL): String =
            baseUrl.trimEnd('/') + "/health"

        fun createMachine(
            coordinator: VoiceSessionCoordinator,
            config: AntiFlappingConfig = AntiFlappingConfig(),
            clock: Clock = SystemClock,
            onEmitTransitionDiagnostic: (from: AutoSwitchState, to: AutoSwitchState, reason: String) -> Unit = { _, _, _ -> },
            onProbeMesh: (suspend () -> Unit)? = null
        ): AutomaticSwitchStateMachine = AutomaticSwitchStateMachine(
            config = config,
            clock = clock,
            onHandoffToOffline = { coordinator.fallbackToMesh() },
            onHandoffToOnline = { coordinator.recoverToLiveKit() },
            onEmitTransitionDiagnostic = onEmitTransitionDiagnostic,
            onProbeMesh = onProbeMesh
        )
    }
}

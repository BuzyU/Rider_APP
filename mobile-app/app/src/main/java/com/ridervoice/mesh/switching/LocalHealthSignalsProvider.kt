package com.ridervoice.mesh.switching

import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.NetworkObserver
import com.ridervoice.utils.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Stage 3 Health Signals Provider.
 *
 * Samples local connectivity signals:
 * - validated Internet capability (via NetworkObserver)
 * - LiveKit connection state & reconnect attempts
 * - Active 3s HEAD health probe to backend
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    suspend fun sampleSignals(): LocalHealthSignals {
        val validatedInternet = isInternetValidated?.invoke()
            ?: (networkObserver?.isConnected() ?: false)

        val lkState = liveKitConnectionState?.value ?: ConnectionState.DISCONNECTED
        val isLkConnected = lkState == ConnectionState.CONNECTED
        val isLkReconnecting = lkState == ConnectionState.RECONNECTING
        val reconnectAttempts = liveKitReconnectAttempts()

        val probeOk = healthProbe?.invoke() ?: executeActiveProbe()

        val validMeshRoute = meshControl?.hasValidRoute?.value ?: false

        return LocalHealthSignals(
            isValidatedInternet = validatedInternet,
            isLiveKitConnected = isLkConnected,
            isLiveKitReconnecting = isLkReconnecting,
            liveKitReconnectAttempts = reconnectAttempts,
            isHealthProbeSuccessful = probeOk,
            hasValidMeshRoute = validMeshRoute
        )
    }

    private fun executeActiveProbe(): Boolean = runCatching {
        val req = Request.Builder()
            .url("${Constants.BASE_URL}health")
            .head()
            .build()
        probeClient.newCall(req).execute().use { response ->
            response.isSuccessful
        }
    }.getOrDefault(false)

    /**
     * Drives the state machine evaluation loop once per second while [isRideActive] is true.
     * When [isRideActive] becomes false, ends the session.
     */
    fun startDriving(
        machine: AutomaticSwitchStateMachine,
        isRideActive: StateFlow<Boolean>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
        pollIntervalMs: Long = 1_000L
    ): Job = scope.launch {
        isRideActive.collectLatest { active ->
            if (active) {
                while (isActive && isRideActive.value) {
                    val signals = sampleSignals()
                    machine.evaluate(signals)
                    delay(pollIntervalMs)
                }
            } else {
                machine.endSession()
            }
        }
    }

    companion object {
        fun createMachine(
            coordinator: VoiceSessionCoordinator,
            config: AntiFlappingConfig = AntiFlappingConfig(),
            clock: () -> Long = { System.currentTimeMillis() },
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

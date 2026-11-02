package com.ridervoice.mesh.switching

import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stage 3 Automatic Online/Offline Switching State Machine with anti-flapping guards.
 *
 * Rules:
 * - Based solely on the local device's online failure / recovery.
 * - Requires failure confirmation delay (default 10s) before falling back to offline mesh.
 * - Requires valid mesh path before declaring OFFLINE_ACTIVE (else FALLBACK_UNAVAILABLE).
 * - Requires recovery stability window (default 8s) before handing back to LiveKit.
 * - Enforces minimum dwell time (default 20s) and transition rate limits (max 3 per 2 mins).
 * - W-DEGRADED: Degraded network alone (high packet loss / high RTT) NEVER triggers suspect / fallback.
 *   Confirmed total loss of internet, health probe failure, and LiveKit disconnection required.
 * - Session lifecycle: beginSession(rideId, nowMs) resets all state, timers, and hysteresis windows.
 */
class AutomaticSwitchStateMachine(
    private val config: AntiFlappingConfig = AntiFlappingConfig(),
    private val isGateAllowed: () -> Boolean = { OfflineMeshGate.isAutomaticFallbackAllowed() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onHandoffToOffline: suspend () -> Boolean = { true },
    private val onHandoffToOnline: suspend () -> Boolean = { true },
    private val onEmitTransitionDiagnostic: (from: AutoSwitchState, to: AutoSwitchState, reason: String) -> Unit = { _, _, _ -> },
    private val onProbeMesh: (suspend () -> Unit)? = null
) {
    constructor(
        config: AntiFlappingConfig = AntiFlappingConfig(),
        isGateAllowed: () -> Boolean = { OfflineMeshGate.isAutomaticFallbackAllowed() },
        clock: Clock,
        onHandoffToOffline: suspend () -> Boolean = { true },
        onHandoffToOnline: suspend () -> Boolean = { true },
        onEmitTransitionDiagnostic: (from: AutoSwitchState, to: AutoSwitchState, reason: String) -> Unit = { _, _, _ -> },
        onProbeMesh: (suspend () -> Unit)? = null
    ) : this(
        config = config,
        isGateAllowed = isGateAllowed,
        clock = { clock.nowMs() },
        onHandoffToOffline = onHandoffToOffline,
        onHandoffToOnline = onHandoffToOnline,
        onEmitTransitionDiagnostic = onEmitTransitionDiagnostic,
        onProbeMesh = onProbeMesh
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AutoSwitchState.ONLINE)
    val state: StateFlow<AutoSwitchState> = _state.asStateFlow()

    private var activeRideId: String? = null
    private var suspectStartTimeMs: Long = 0L
    private var recoveryStartTimeMs: Long = 0L
    private var lastSwitchTimeMs: Long = 0L
    private var startingSinceMs: Long = 0L
    private var handingBackSinceMs: Long = 0L
    private var lastRecoveryAttemptMs: Long = 0L

    private val switchHistory = ArrayDeque<Long>()
    private var backoffMultiplier: Int = 1
    private var recoveryBackoffMultiplier: Int = 1

    /**
     * Explicit session lifecycle initialization.
     * Clears all anti-flapping timers, backoff counters, and hysteresis windows.
     * Sets state to ONLINE (stable online start). Never carries state between rides.
     */
    suspend fun beginSession(rideId: String, nowMs: Long = clock()) = mutex.withLock {
        activeRideId = rideId
        suspectStartTimeMs = 0L
        recoveryStartTimeMs = 0L
        lastSwitchTimeMs = 0L
        startingSinceMs = 0L
        handingBackSinceMs = 0L
        lastRecoveryAttemptMs = 0L
        switchHistory.clear()
        backoffMultiplier = 1
        recoveryBackoffMultiplier = 1
        _state.value = AutoSwitchState.ONLINE
    }

    suspend fun evaluate(
        signals: LocalHealthSignals,
        offlineAvailable: Boolean = signals.offlineAvailable
    ) = mutex.withLock {
        val now = clock()
        val current = _state.value

        if (current == AutoSwitchState.SESSION_ENDED) return

        // Rule 3: Do not enter any fallback/standby transition when OfflineAvailability != AVAILABLE
        if (!offlineAvailable) {
            if (current == AutoSwitchState.ONLINE_SUSPECT || current == AutoSwitchState.FALLBACK_UNAVAILABLE) {
                transitionTo(AutoSwitchState.ONLINE, "NO_SHARED_SECRET")
            } else if (current == AutoSwitchState.ONLINE && signals.isTotalLoss()) {
                onEmitTransitionDiagnostic(AutoSwitchState.ONLINE, AutoSwitchState.ONLINE, "NO_SHARED_SECRET")
            }
            return
        }

        when (current) {
            AutoSwitchState.ONLINE -> {
                if (signals.isOnlineFailing() && isGateAllowed()) {
                    suspectStartTimeMs = now
                    transitionTo(AutoSwitchState.ONLINE_SUSPECT, "Local online failure suspected")
                    onProbeMesh?.invoke()
                }
            }

            AutoSwitchState.ONLINE_SUSPECT -> {
                onProbeMesh?.invoke()
                if (signals.isOnlineHealthy()) {
                    suspectStartTimeMs = 0L
                    transitionTo(AutoSwitchState.ONLINE, "Local online recovered during suspect window")
                } else if (now - suspectStartTimeMs >= config.failureConfirmationMs * backoffMultiplier) {
                    if (isFlappingSuppressed(now)) {
                        // Rate-limited; stay in suspect with increased backoff
                        suspectStartTimeMs = now
                        backoffMultiplier = (backoffMultiplier * 2).coerceAtMost(8)
                        return
                    }
                    if (signals.hasValidMeshRoute) {
                        tryHandoffOffline(now)
                    } else {
                        transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Failure confirmed but no mesh route available")
                    }
                }
            }

            AutoSwitchState.FALLBACK_UNAVAILABLE -> {
                if (signals.isOnlineHealthy()) {
                    transitionTo(AutoSwitchState.ONLINE, "Online restored from fallback unavailable")
                } else if (signals.hasValidMeshRoute && isGateAllowed()) {
                    if (isFlappingSuppressed(now)) {
                        return
                    }
                    tryHandoffOffline(now)
                }
            }

            AutoSwitchState.OFFLINE_ACTIVE -> {
                val inDwell = (now - lastSwitchTimeMs) < config.minDwellMs
                val inRecoveryBackoff = (now - lastRecoveryAttemptMs) < config.recoveryStabilityMs * recoveryBackoffMultiplier
                if (!inDwell && !inRecoveryBackoff && signals.isOnlineRecoverable()) {
                    recoveryStartTimeMs = now
                    transitionTo(AutoSwitchState.ONLINE_RECOVERING, "Online recoverable, verifying stability window")
                }
            }

            AutoSwitchState.ONLINE_RECOVERING -> {
                if (!signals.isOnlineRecoverable()) {
                    recoveryStartTimeMs = 0L
                    transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online instability during recovery window; returning to mesh")
                } else if (now - recoveryStartTimeMs >= config.recoveryStabilityMs) {
                    tryHandoffOnline(now)
                }
            }

            AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> {
                if (now - handingBackSinceMs > 15_000L) {
                    lastRecoveryAttemptMs = now
                    recoveryBackoffMultiplier = (recoveryBackoffMultiplier * 2).coerceAtMost(32)
                    transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online handoff timed out; staying on mesh")
                }
            }

            AutoSwitchState.STARTING_OFFLINE_MESH -> {
                if (now - startingSinceMs > 15_000L) {
                    transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Offline handoff timed out")
                }
            }

            AutoSwitchState.SESSION_ENDED -> Unit
        }
    }

    private suspend fun tryHandoffOffline(now: Long) {
        startingSinceMs = now
        transitionTo(AutoSwitchState.STARTING_OFFLINE_MESH, "Starting offline mesh")
        val handoffSuccess = try {
            onHandoffToOffline()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        if (handoffSuccess) {
            recordSwitch(now)
            lastSwitchTimeMs = now
            backoffMultiplier = 1
            transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Offline mesh active")
        } else {
            transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Offline handoff failed")
        }
    }

    private suspend fun tryHandoffOnline(now: Long) {
        handingBackSinceMs = now
        transitionTo(AutoSwitchState.HANDING_BACK_TO_LIVEKIT, "Recovery window satisfied, handing back to LiveKit")
        val handoffSuccess = try {
            onHandoffToOnline()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        if (handoffSuccess) {
            recordSwitch(now)
            lastSwitchTimeMs = now
            backoffMultiplier = 1
            recoveryBackoffMultiplier = 1
            transitionTo(AutoSwitchState.ONLINE, "Online voice restored")
        } else {
            lastRecoveryAttemptMs = now
            recoveryBackoffMultiplier = (recoveryBackoffMultiplier * 2).coerceAtMost(32)
            transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online handoff failed; staying on mesh")
        }
    }

    suspend fun endSession() = mutex.withLock {
        activeRideId = null
        suspectStartTimeMs = 0L
        recoveryStartTimeMs = 0L
        lastSwitchTimeMs = 0L
        startingSinceMs = 0L
        handingBackSinceMs = 0L
        lastRecoveryAttemptMs = 0L
        switchHistory.clear()
        backoffMultiplier = 1
        recoveryBackoffMultiplier = 1
        transitionTo(AutoSwitchState.SESSION_ENDED, "Session ended")
    }

    private fun transitionTo(next: AutoSwitchState, reason: String) {
        val prev = _state.value
        if (prev != next) {
            _state.value = next
            onEmitTransitionDiagnostic(prev, next, reason)
        }
    }

    private fun recordSwitch(now: Long) {
        switchHistory.addLast(now)
        pruneSwitchHistory(now)
    }

    private fun isFlappingSuppressed(now: Long): Boolean {
        pruneSwitchHistory(now)
        return switchHistory.size >= config.maxTransitionsPerWindow
    }

    private fun pruneSwitchHistory(now: Long) {
        while (switchHistory.isNotEmpty() && now - switchHistory.first() > config.windowMs) {
            switchHistory.removeFirst()
        }
    }
}

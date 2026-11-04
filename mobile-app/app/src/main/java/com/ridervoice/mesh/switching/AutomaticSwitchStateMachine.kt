package com.ridervoice.mesh.switching

import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

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

    private enum class Direction { TO_OFFLINE, TO_ONLINE }
    private class Pending(val dir: Direction, val ticket: Long)
    private var ticket = 0L
    private companion object {
        const val HANDOFF_TIMEOUT_MS = 20_000L
        const val TAG = "SwitchSM"
    }

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
        ticket++
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
    ) {
        var pending: Pending? = null
        mutex.withLock {
            val now = clock()
            val current = _state.value

            if (current == AutoSwitchState.SESSION_ENDED) return

            // A25: availability only blocks ENTRY states. Recovery states must always keep running.
            val entryState = current == AutoSwitchState.ONLINE ||
                current == AutoSwitchState.ONLINE_SUSPECT ||
                current == AutoSwitchState.FALLBACK_UNAVAILABLE
            if (!offlineAvailable && entryState) {
                if (current != AutoSwitchState.ONLINE) {
                    transitionTo(AutoSwitchState.ONLINE, "NO_SHARED_SECRET")
                } else if (signals.isTotalLoss()) {
                    onEmitTransitionDiagnostic(current, current, "NO_SHARED_SECRET")
                }
                return
            }

            when (current) {
                AutoSwitchState.ONLINE -> {
                    if (signals.isOnlineFailing() && isGateAllowed()) {
                        suspectStartTimeMs = now
                        transitionTo(AutoSwitchState.ONLINE_SUSPECT, "Total loss suspected")
                        onProbeMesh?.invoke()
                    }
                }

                AutoSwitchState.ONLINE_SUSPECT -> {
                    onProbeMesh?.invoke()
                    if (!signals.isTotalLoss()) {
                        suspectStartTimeMs = 0L
                        transitionTo(AutoSwitchState.ONLINE, "Total loss cleared")
                    } else if (now - suspectStartTimeMs >= config.failureConfirmationMs * backoffMultiplier) {
                        if (isFlappingSuppressed(now)) {
                            suspectStartTimeMs = now
                            backoffMultiplier = (backoffMultiplier * 2).coerceAtMost(8)
                        } else if (signals.hasValidMeshRoute) {
                            pending = beginHandoff(Direction.TO_OFFLINE, now)
                        } else {
                            transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Total loss confirmed, no mesh route")
                        }
                    }
                }

                AutoSwitchState.FALLBACK_UNAVAILABLE -> when {
                    !signals.isTotalLoss() -> transitionTo(AutoSwitchState.ONLINE, "Total loss cleared")
                    signals.hasValidMeshRoute && isGateAllowed() && !isFlappingSuppressed(now) ->
                        pending = beginHandoff(Direction.TO_OFFLINE, now)
                }

                AutoSwitchState.OFFLINE_ACTIVE -> {
                    val inDwell = (now - lastSwitchTimeMs) < config.minDwellMs
                    val inBackoff = (now - lastRecoveryAttemptMs) < config.recoveryStabilityMs * recoveryBackoffMultiplier
                    if (!signals.isMeshTransportAlive && signals.isOnlineRecoverable()) {
                        pending = beginHandoff(Direction.TO_ONLINE, now)
                    } else if (!inDwell && !inBackoff && signals.isOnlineRecoverable()) {
                        recoveryStartTimeMs = now
                        transitionTo(AutoSwitchState.ONLINE_RECOVERING, "Online recoverable, verifying stability")
                    }
                }

                AutoSwitchState.ONLINE_RECOVERING -> when {
                    !signals.isOnlineRecoverable() -> {
                        recoveryStartTimeMs = 0L
                        transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online unstable; staying on mesh")
                    }
                    now - recoveryStartTimeMs >= config.recoveryStabilityMs -> {
                        pending = beginHandoff(Direction.TO_ONLINE, now)
                    }
                }

                AutoSwitchState.HANDING_BACK_TO_LIVEKIT, AutoSwitchState.STARTING_OFFLINE_MESH, AutoSwitchState.SESSION_ENDED -> Unit
            }
        }
        pending?.let { runHandoff(it) }
    }

    private fun beginHandoff(dir: Direction, now: Long): Pending {
        ticket++
        if (dir == Direction.TO_OFFLINE) {
            transitionTo(AutoSwitchState.STARTING_OFFLINE_MESH, "Starting offline mesh")
        } else {
            transitionTo(AutoSwitchState.HANDING_BACK_TO_LIVEKIT, "Recovery window satisfied")
        }
        return Pending(dir, ticket)
    }

    private suspend fun runHandoff(p: Pending) {
        val ok: Boolean = try {
            withTimeout(HANDOFF_TIMEOUT_MS) {
                if (p.dir == Direction.TO_OFFLINE) onHandoffToOffline() else onHandoffToOnline()
            }
        } catch (e: TimeoutCancellationException) {
            MeshLog.w(TAG, "handoff ${p.dir} timed out")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MeshLog.w(TAG, "handoff ${p.dir} failed: ${e.javaClass.simpleName}")
            false
        }

        mutex.withLock {
            if (p.ticket != ticket || _state.value == AutoSwitchState.SESSION_ENDED) return
            val now = clock()
            if (p.dir == Direction.TO_OFFLINE) {
                if (ok) {
                    recordSwitch(now)
                    lastSwitchTimeMs = now
                    backoffMultiplier = 1
                    transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Offline mesh active")
                } else {
                    transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Offline handoff failed")
                }
            } else {
                if (ok) {
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
        }
    }

    suspend fun endSession() = mutex.withLock {
        ticket++
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

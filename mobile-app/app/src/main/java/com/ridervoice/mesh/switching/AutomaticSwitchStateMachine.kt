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
 */
class AutomaticSwitchStateMachine(
    private val config: AntiFlappingConfig = AntiFlappingConfig(),
    private val isGateAllowed: () -> Boolean = { OfflineMeshGate.isAutomaticFallbackAllowed() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onHandoffToOffline: suspend () -> Boolean = { true },
    private val onHandoffToOnline: suspend () -> Boolean = { true },
    private val onEmitTransitionDiagnostic: (from: AutoSwitchState, to: AutoSwitchState, reason: String) -> Unit = { _, _, _ -> }
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AutoSwitchState.ONLINE)
    val state: StateFlow<AutoSwitchState> = _state.asStateFlow()

    private var suspectStartTimeMs: Long = 0L
    private var recoveryStartTimeMs: Long = 0L
    private var lastSwitchTimeMs: Long = 0L

    private val switchHistory = ArrayDeque<Long>()
    private var backoffMultiplier: Int = 1

    suspend fun evaluate(signals: LocalHealthSignals) = mutex.withLock {
        val now = clock()
        val current = _state.value

        if (current == AutoSwitchState.SESSION_ENDED) return

        when (current) {
            AutoSwitchState.ONLINE -> {
                if (signals.isOnlineFailing() && isGateAllowed()) {
                    suspectStartTimeMs = now
                    transitionTo(AutoSwitchState.ONLINE_SUSPECT, "Local online failure suspected")
                }
            }

            AutoSwitchState.ONLINE_SUSPECT -> {
                if (signals.isOnlineHealthy()) {
                    suspectStartTimeMs = 0L
                    transitionTo(AutoSwitchState.ONLINE, "Local online recovered during suspect window")
                } else if (now - suspectStartTimeMs >= config.failureConfirmationMs * backoffMultiplier) {
                    if (isFlappingSuppressed(now)) {
                        // Rate-limited; stay in suspect with increased backoff
                        backoffMultiplier = (backoffMultiplier * 2).coerceAtMost(8)
                        return
                    }
                    if (signals.hasValidMeshRoute) {
                        transitionTo(AutoSwitchState.STARTING_OFFLINE_MESH, "Failure confirmed, starting mesh")
                        val handoffSuccess = onHandoffToOffline()
                        if (handoffSuccess) {
                            recordSwitch(now)
                            lastSwitchTimeMs = now
                            transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Offline mesh active")
                        } else {
                            transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Offline handoff failed")
                        }
                    } else {
                        transitionTo(AutoSwitchState.FALLBACK_UNAVAILABLE, "Failure confirmed but no mesh route available")
                    }
                }
            }

            AutoSwitchState.FALLBACK_UNAVAILABLE -> {
                if (signals.isOnlineHealthy()) {
                    transitionTo(AutoSwitchState.ONLINE, "Online restored from fallback unavailable")
                } else if (signals.hasValidMeshRoute && isGateAllowed()) {
                    transitionTo(AutoSwitchState.STARTING_OFFLINE_MESH, "Valid mesh route became available")
                    val handoffSuccess = onHandoffToOffline()
                    if (handoffSuccess) {
                        recordSwitch(now)
                        lastSwitchTimeMs = now
                        transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Offline mesh active")
                    }
                }
            }

            AutoSwitchState.OFFLINE_ACTIVE -> {
                val inDwell = (now - lastSwitchTimeMs) < config.minDwellMs
                if (!inDwell && signals.isOnlineHealthy()) {
                    recoveryStartTimeMs = now
                    transitionTo(AutoSwitchState.ONLINE_RECOVERING, "Online healthy, verifying stability window")
                }
            }

            AutoSwitchState.ONLINE_RECOVERING -> {
                if (!signals.isOnlineHealthy()) {
                    recoveryStartTimeMs = 0L
                    transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online instability during recovery window; returning to mesh")
                } else if (now - recoveryStartTimeMs >= config.recoveryStabilityMs) {
                    transitionTo(AutoSwitchState.HANDING_BACK_TO_LIVEKIT, "Recovery window satisfied, handing back to LiveKit")
                    val handoffSuccess = onHandoffToOnline()
                    if (handoffSuccess) {
                        recordSwitch(now)
                        lastSwitchTimeMs = now
                        backoffMultiplier = 1
                        transitionTo(AutoSwitchState.ONLINE, "Online voice restored")
                    } else {
                        transitionTo(AutoSwitchState.OFFLINE_ACTIVE, "Online handoff failed; staying on mesh")
                    }
                }
            }

            AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> {
                // Handled inline in ONLINE_RECOVERING
            }

            AutoSwitchState.STARTING_OFFLINE_MESH -> {
                // Handled inline in ONLINE_SUSPECT / FALLBACK_UNAVAILABLE
            }

            AutoSwitchState.SESSION_ENDED -> Unit
        }
    }

    suspend fun endSession() = mutex.withLock {
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


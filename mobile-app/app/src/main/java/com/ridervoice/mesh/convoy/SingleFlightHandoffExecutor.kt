package com.ridervoice.mesh.convoy

/**
 * Pure Kotlin single-flight handoff serializer (Section 12).
 *
 * - Never runs fallback and recovery concurrently.
 * - Never cancels an in-flight handoff mid-way.
 * - Drops superseded queued decisions before starting.
 * - On completion/rollback, compares actual local transport against the *latest* desired decision.
 * - Enforces bounded exponential backoff on repeated local handoff failures.
 */
class SingleFlightHandoffExecutor(
    private val baseRetryBackoffMs: Long = 2_000L,
    private val maxRetryBackoffMs: Long = 32_000L,
    private val maxConsecutiveFailures: Int = 6
) {
    sealed interface Command {
        data class RunFallback(val decision: TransportDecision) : Command
        data class RunRecovery(val decision: TransportDecision) : Command
    }

    enum class ActualTransport { ONLINE, OFFLINE, UNAVAILABLE }

    private var desiredDecision: TransportDecision? = null
    private var actualTransport: ActualTransport = ActualTransport.ONLINE
    private var inFlightDecision: TransportDecision? = null
    private var consecutiveFailures: Int = 0
    private var nextRetryAllowedAtMs: Long = 0L

    fun onDesiredDecision(decision: TransportDecision) {
        val prev = desiredDecision
        if (prev == null || DecisionOrdering.compare(decision, prev) == DecisionComparison.CandidateWins) {
            desiredDecision = decision
            // New higher decision clears stale retry backoff
            consecutiveFailures = 0
            nextRetryAllowedAtMs = 0L
        }
    }

    fun setActualTransport(actual: ActualTransport) {
        actualTransport = actual
    }

    fun isInFlight(): Boolean = inFlightDecision != null
    fun currentDesiredDecision(): TransportDecision? = desiredDecision
    fun currentActualTransport(): ActualTransport = actualTransport

    /**
     * Returns the next handoff command if idle, prerequisites hold, and actual differs from desired.
     */
    fun pollNextCommand(
        nowMs: Long,
        canFallbackToMesh: Boolean,
        canRecoverToLiveKit: Boolean
    ): Command? {
        if (inFlightDecision != null) return null
        val desired = desiredDecision ?: return null
        if (nowMs < nextRetryAllowedAtMs || consecutiveFailures >= maxConsecutiveFailures) {
            return null
        }

        return when (desired.mode) {
            TransportMode.OFFLINE -> {
                if (actualTransport == ActualTransport.OFFLINE) return null
                if (!canFallbackToMesh) return null
                inFlightDecision = desired
                Command.RunFallback(desired)
            }
            TransportMode.ONLINE -> {
                if (desired.phase != DecisionPhase.ONLINE_COMMITTED) return null
                if (actualTransport == ActualTransport.ONLINE) return null
                if (!canRecoverToLiveKit) return null
                inFlightDecision = desired
                Command.RunRecovery(desired)
            }
        }
    }

    fun onHandoffFinished(decisionId: ByteArray, success: Boolean, nowMs: Long) {
        val active = inFlightDecision ?: return
        if (!active.decisionId.contentEquals(decisionId)) return
        inFlightDecision = null

        if (success) {
            actualTransport = when (active.mode) {
                TransportMode.OFFLINE -> ActualTransport.OFFLINE
                TransportMode.ONLINE -> ActualTransport.ONLINE
            }
            consecutiveFailures = 0
            nextRetryAllowedAtMs = 0L
        } else {
            consecutiveFailures++
            val shift = (consecutiveFailures - 1).coerceIn(0, 5)
            val delayMs = (baseRetryBackoffMs shl shift).coerceAtMost(maxRetryBackoffMs)
            nextRetryAllowedAtMs = nowMs + delayMs
        }
    }

    fun resetRetryBudget() {
        consecutiveFailures = 0
        nextRetryAllowedAtMs = 0L
    }
}

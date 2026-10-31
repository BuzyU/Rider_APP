package com.ridervoice.mesh.forwarding

import com.ridervoice.mesh.MeshConfig
import kotlin.math.abs

/**
 * Enforces TTL bounds, packet age validation, and per-source forwarding rate budgets.
 */
class TtlPolicy(
    val defaultTtl: Int = MeshConfig.DEFAULT_TTL,
    private val maxClockSkewMs: Long = MeshConfig.MAX_CLOCK_SKEW_MS,
    private val forwardBudgetPerSec: Int = MeshConfig.FORWARD_BUDGET_PER_SEC,
    private val clock: () -> Long
) {
    private class WindowCounter(var windowStartMs: Long, var count: Int)
    private val budgetCounters = HashMap<String, WindowCounter>()

    fun isTtlValidOnReceive(ttl: Int): Boolean = ttl in 1..defaultTtl

    fun canForwardAfterReceive(ttlOnReceive: Int): Boolean = ttlOnReceive > 1

    fun isTimestampFresh(captureTimestamp: Long): Boolean {
        val now = clock()
        return abs(now - captureTimestamp) <= maxClockSkewMs
    }

    @Synchronized
    fun tryConsumeForwardBudget(sourceId: String): Boolean {
        val now = clock()
        val counter = budgetCounters[sourceId]
        if (counter == null || now - counter.windowStartMs >= 1_000L) {
            budgetCounters[sourceId] = WindowCounter(now, 1)
            return true
        }
        if (counter.count >= forwardBudgetPerSec) {
            return false
        }
        counter.count++
        return true
    }

    @Synchronized
    fun reset() {
        budgetCounters.clear()
    }
}


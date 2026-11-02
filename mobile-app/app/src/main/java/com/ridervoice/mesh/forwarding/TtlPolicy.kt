package com.ridervoice.mesh.forwarding

import com.ridervoice.mesh.MeshConfig
import kotlin.math.abs

/**
 * Enforces TTL bounds, packet age validation, and per-source forwarding rate budgets.
 */
class TtlPolicy(
    val defaultTtl: Int = MeshConfig.DEFAULT_TTL,
    val maxHops: Int = MeshConfig.MAX_HOPS,
    private val maxClockSkewMs: Long = MeshConfig.MAX_CLOCK_SKEW_MS,
    private val forwardBudgetPerSec: Int = MeshConfig.FORWARD_BUDGET_PER_SEC,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private class WindowCounter(var windowStartMs: Long, var count: Int)
    private val budgetCounters = HashMap<String, WindowCounter>()

    /**
     * Drops a packet when ttl == 0, hopCount > MAX_HOPS (7), or ttl + hopCount > DEFAULT_TTL.
     * Both control and audio share this unified TTL rule.
     */
    fun isTtlValidOnReceive(ttl: Int, hopCount: Int = 0): Boolean {
        if (ttl <= 0) return false
        if (hopCount > maxHops) return false
        if (ttl + hopCount > defaultTtl) return false
        return true
    }

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


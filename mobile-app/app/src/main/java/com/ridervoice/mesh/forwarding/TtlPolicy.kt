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
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val monotonicClock: () -> Long = clock
) {
    constructor(clock: () -> Long) : this(
        defaultTtl = MeshConfig.DEFAULT_TTL,
        maxHops = MeshConfig.MAX_HOPS,
        maxClockSkewMs = MeshConfig.MAX_CLOCK_SKEW_MS,
        forwardBudgetPerSec = MeshConfig.FORWARD_BUDGET_PER_SEC,
        clock = clock,
        monotonicClock = clock
    )

    private class WindowCounter(var windowStartMs: Long, var count: Int)
    private val budgetCounters = HashMap<String, WindowCounter>()

    /**
     * Drops a packet when ttl == 0, hopCount > MAX_HOPS (5), or ttl + hopCount > DEFAULT_TTL (5).
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
        val now = monotonicClock()
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


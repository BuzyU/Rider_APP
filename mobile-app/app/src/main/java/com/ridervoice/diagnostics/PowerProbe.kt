package com.ridervoice.diagnostics

import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WP-143: Diagnostics power and resource utilization probe.
 *
 * Tracks hardware wake lock held duration, GPS fix frequency, mesh packet throughput,
 * and HTTP poll frequency to detect and measure battery drain during lobby, online rides,
 * and offline mesh operation.
 */
object PowerProbe {

    data class PowerMetricsSnapshot(
        val totalWakeLockHeldMs: Long,
        val isWakeLockHeld: Boolean,
        val activeWakeLockDurationMs: Long,
        val gpsFixesPerMinute: Int,
        val meshPacketsPerMinute: Int,
        val pollsPerMinute: Int
    )

    private val wakeLockAcquireTimeMs = AtomicLong(0L)
    private val totalWakeLockHeldDurationMs = AtomicLong(0L)
    private val isWakeLockHeldFlag = AtomicBoolean(false)

    private val gpsFixTimestamps = ConcurrentLinkedQueue<Long>()
    private val meshPacketTimestamps = ConcurrentLinkedQueue<Long>()
    private val pollTimestamps = ConcurrentLinkedQueue<Long>()

    private const val WINDOW_MS = 60_000L

    var clockSupplier: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }
    }

    fun recordWakeLockAcquired(tag: String? = null) {
        val now = clockSupplier()
        if (isWakeLockHeldFlag.compareAndSet(false, true)) {
            wakeLockAcquireTimeMs.set(now)
        }
    }

    fun recordWakeLockReleased(tag: String? = null) {
        val now = clockSupplier()
        if (isWakeLockHeldFlag.compareAndSet(true, false)) {
            val acquiredAt = wakeLockAcquireTimeMs.getAndSet(0L)
            if (acquiredAt > 0L && now >= acquiredAt) {
                totalWakeLockHeldDurationMs.addAndGet(now - acquiredAt)
            }
        }
    }

    fun recordGpsFix() {
        val now = clockSupplier()
        gpsFixTimestamps.add(now)
        pruneOldEntries(gpsFixTimestamps, now)
    }

    fun recordMeshPacket() {
        val now = clockSupplier()
        meshPacketTimestamps.add(now)
        pruneOldEntries(meshPacketTimestamps, now)
    }

    fun recordPoll() {
        val now = clockSupplier()
        pollTimestamps.add(now)
        pruneOldEntries(pollTimestamps, now)
    }

    private fun pruneOldEntries(queue: ConcurrentLinkedQueue<Long>, now: Long) {
        val cutoff = now - WINDOW_MS
        while (true) {
            val head = queue.peek() ?: break
            if (head < cutoff) {
                queue.poll()
            } else {
                break
            }
        }
    }

    fun snapshot(): PowerMetricsSnapshot {
        val now = clockSupplier()
        pruneOldEntries(gpsFixTimestamps, now)
        pruneOldEntries(meshPacketTimestamps, now)
        pruneOldEntries(pollTimestamps, now)

        val currentlyHeld = isWakeLockHeldFlag.get()
        val acquiredAt = wakeLockAcquireTimeMs.get()
        val activeDuration = if (currentlyHeld && acquiredAt > 0L) (now - acquiredAt).coerceAtLeast(0L) else 0L
        val totalMs = totalWakeLockHeldDurationMs.get() + activeDuration

        return PowerMetricsSnapshot(
            totalWakeLockHeldMs = totalMs,
            isWakeLockHeld = currentlyHeld,
            activeWakeLockDurationMs = activeDuration,
            gpsFixesPerMinute = gpsFixTimestamps.size,
            meshPacketsPerMinute = meshPacketTimestamps.size,
            pollsPerMinute = pollTimestamps.size
        )
    }

    fun reset() {
        clockSupplier = { runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() } }
        wakeLockAcquireTimeMs.set(0L)
        totalWakeLockHeldDurationMs.set(0L)
        isWakeLockHeldFlag.set(false)
        gpsFixTimestamps.clear()
        meshPacketTimestamps.clear()
        pollTimestamps.clear()
    }
}

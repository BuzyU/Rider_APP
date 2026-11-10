package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterDriftCatchUpTest {

    @Test
    fun fasterSender_300ppm_driftsAndCatchesUpWithoutExceedingDepth() {
        var rxClock = 10_000.0
        val buffer = SourceJitterBuffer(
            sourceId = "node-drift-fast",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { rxClock.toLong() }
        )
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x11 }

        // Prime buffer
        buffer.enqueue(0, dummyPcm)
        buffer.enqueue(1, dummyPcm)
        buffer.enqueue(2, dummyPcm)

        // 300 ppm faster sender: emits a frame every 19.994 ms
        val senderInterval = 20.0 * (1.0 - 0.0003)
        var senderTime = 10_000.0
        var seq = 3

        val initialOverflow = buffer.overflowDroppedCount

        // Simulate 10 minutes (600 seconds)
        // 600 seconds / 0.02s = 30,000 receiver ticks
        for (tick in 0 until 30_000) {
            rxClock += 20.0
            while (senderTime <= rxClock) {
                buffer.enqueue(seq++, dummyPcm)
                senderTime += senderInterval
            }
            buffer.pollFrame()

            assertTrue(
                "Queue depth must never exceed target + 2 (was ${buffer.depthFrames}) at tick $tick",
                buffer.depthFrames <= buffer.targetFrames + 2
            )
        }

        val totalDropped = buffer.overflowDroppedCount - initialOverflow
        // Over 600 seconds, total dropped frames must be <= 600 (at most 1 dropped frame per second)
        assertTrue("Dropped frames must be <= 1 per second over 600s (was $totalDropped)", totalDropped <= 600)
    }

    @Test
    fun slowerSender_300ppm_driftsWithoutExcessiveConcealment() {
        var rxClock = 10_000.0
        val buffer = SourceJitterBuffer(
            sourceId = "node-drift-slow",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { rxClock.toLong() }
        )
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x22 }

        buffer.enqueue(0, dummyPcm)
        buffer.enqueue(1, dummyPcm)
        buffer.enqueue(2, dummyPcm)

        // 300 ppm slower sender: emits a frame every 20.006 ms
        val senderInterval = 20.0 * (1.0 + 0.0003)
        var senderTime = 10_000.0
        var seq = 3

        var concealedTicks = 0

        // Simulate 60 seconds (3,000 receiver ticks)
        for (tick in 0 until 3_000) {
            rxClock += 20.0
            while (senderTime <= rxClock) {
                buffer.enqueue(seq++, dummyPcm)
                senderTime += senderInterval
            }
            val frame = buffer.pollFrame()
            if (frame is PlayoutFrame.Concealed) {
                concealedTicks++
            }
        }

        assertTrue("Slower sender by 300 ppm must produce <= 1 concealed frame per 60 s (was $concealedTicks)", concealedTicks <= 1)
    }
}


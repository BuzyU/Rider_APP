package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterStandingDelayTest {

    @Test
    fun standingDelayFix_preventsQueuedStandingLatencyAfterStall() {
        var time = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-vp14",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { time }
        )

        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x44 }

        // Prime with initial frames 0, 1, 2
        buffer.enqueue(0, dummyPcm)
        buffer.enqueue(1, dummyPcm)
        buffer.enqueue(2, dummyPcm)
        assertTrue(buffer.isPrimed())

        assertEquals(0, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)

        // 100-frame hole occurs (stall/loss). Playout attempts a poll during the hole:
        // Frame 1 and 2 are in queue, so 1 and 2 play:
        assertEquals(1, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(2, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)

        // Now queue is empty. Playout does 1 poll -> missing frame 3 -> concealed
        val missedPoll = buffer.pollFrame()
        assertTrue("Missed poll produces concealed", missedPoll is PlayoutFrame.Concealed)

        // After stall, sender bursts in frames 104, 105, 106, followed by steady 1-frame/tick arrivals
        buffer.enqueue(104, dummyPcm)
        buffer.enqueue(105, dummyPcm)
        buffer.enqueue(106, dummyPcm)

        var totalConcealedTicks = 1 // the one prior to burst

        // Simulate 50 ticks of steady 1-frame-in, 1-frame-out
        for (seq in 107..156) {
            buffer.enqueue(seq, dummyPcm)
            time += 20L
            val playout = buffer.pollFrame()
            if (playout is PlayoutFrame.Concealed) {
                totalConcealedTicks++
            }
            assertTrue(
                "Queue depth must never exceed target + 2 (was ${buffer.depthFrames}) at seq $seq",
                buffer.depthFrames <= buffer.targetFrames + 2
            )
        }

        assertTrue("Total concealed ticks during 100-frame gap must be <= 2 (was $totalConcealedTicks)", totalConcealedTicks <= 2)
    }
}


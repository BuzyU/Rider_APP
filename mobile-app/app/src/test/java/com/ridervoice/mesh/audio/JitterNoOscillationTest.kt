package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterNoOscillationTest {

    @Test
    fun steadySlightJitter_doesNotCauseTargetOscillation() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-stable",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x33 }

        var targetChanges = 0
        var prevTarget = buffer.targetFrames

        // Prime buffer
        buffer.enqueue(0, dummyPcm)
        buffer.enqueue(1, dummyPcm)
        buffer.enqueue(2, dummyPcm)

        var seq = 3

        // Simulate 60 seconds (3,000 frames) with alternating 18ms and 22ms intervals (20ms +- 2ms)
        for (i in 0 until 3_000) {
            val interval = if (i % 2 == 0) 18L else 22L
            now += interval
            buffer.enqueue(seq++, dummyPcm)
            buffer.pollFrame()

            val curTarget = buffer.targetFrames
            if (curTarget != prevTarget) {
                targetChanges++
                prevTarget = curTarget
            }
        }

        assertTrue(
            "Steady 20ms +-2ms arrivals for 60s must never change target more than once (was $targetChanges)",
            targetChanges <= 1
        )
    }
}


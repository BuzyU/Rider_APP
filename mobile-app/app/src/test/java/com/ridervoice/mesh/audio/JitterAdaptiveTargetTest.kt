package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterAdaptiveTargetTest {

    @Test
    fun adaptiveTarget_adaptsToJitterAndReturnsToRest() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-adaptive",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        // Initial target is 3
        assertEquals(3, buffer.targetFrames)

        // 1. Send steady 20ms packets for > 2.5 seconds (e.g. 130 packets) with 0ms jitter
        var seq = 0
        for (i in 0 until 130) {
            buffer.enqueue(seq++, dummyPcm)
            now += 20L
            buffer.pollFrame()
        }

        // With 0 ms jitter over > 2 s, target must have shrunk to 2
        assertEquals("0 ms jitter over >2s must shrink target to 2", 2, buffer.targetFrames)

        // 2. Inject bursts with ±40 ms jitter (inter-arrival intervals alternating 60ms and 0ms)
        for (i in 0 until 30) {
            val delta = if (i % 2 == 0) 60L else 0L
            now += delta
            buffer.enqueue(seq++, dummyPcm)
            if (i % 2 == 0) {
                now += 20L
                buffer.pollFrame()
            }
        }

        assertTrue("Bursts with 40ms jitter must expand target (target=${buffer.targetFrames})", buffer.targetFrames >= 4 && buffer.targetFrames <= 6)

        // 3. Return to steady 20 ms arrivals for 5 simulated seconds
        for (i in 0 until 250) {
            now += 20L
            buffer.enqueue(seq++, dummyPcm)
            buffer.pollFrame()
        }

        assertTrue("Target must return to 2..3 after stable arrivals (was ${buffer.targetFrames})", buffer.targetFrames in 2..3)
    }
}


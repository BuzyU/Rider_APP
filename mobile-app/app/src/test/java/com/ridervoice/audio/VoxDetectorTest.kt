package com.ridervoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VoxDetectorTest {

    private val rnd = Random(42)

    /** White-noise frame scaled to the requested (pre-filter) RMS. */
    private fun noiseFrame(rms: Float): ShortArray {
        val f = ShortArray(VoxDetector.FRAME)
        for (i in f.indices) f[i] = (rnd.nextGaussian() * rms).toInt().coerceIn(-32000, 32000).toShort()
        return f
    }

    private class Sim(val d: VoxDetector) {
        var now = 1_000L
        var open = false
        var openedAt = -1L
        var closedAt = -1L
        var opens = 0

        fun step(level: Float, gen: (Float) -> ShortArray) {
            val frame = gen(level)
            val r = d.rms(frame, frame.size)
            d.updateFloor(r)
            when (d.evaluate(r, now, open)) {
                VoxDetector.Decision.OPEN -> { open = true; openedAt = now; opens++ }
                VoxDetector.Decision.CLOSE -> { open = false; closedAt = now }
                VoxDetector.Decision.NONE -> {}
            }
            now += 20
        }
    }

    @Test
    fun steadyNoiseConvergesAndNeverOpens() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) } // 10 s
        assertFalse(sim.open)
        assertEquals(0, sim.opens)
        assertEquals(400f, sim.d.floor, 400f * 0.2f)
    }

    @Test
    fun burstOpensQuicklyAndClosesAfterHold() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) }
        val burstStart = sim.now
        repeat(10) { sim.step(400f * 6f, ::noiseFrame) } // 200 ms burst
        assertTrue(sim.open)
        assertTrue("open latency ${sim.openedAt - burstStart}", sim.openedAt - burstStart <= 80)
        val burstEnd = sim.now
        repeat(80) { sim.step(400f, ::noiseFrame) }
        assertFalse(sim.open)
        val closeDelay = sim.closedAt - burstEnd
        assertTrue("close delay $closeDelay", closeDelay in 700..1100)
    }

    @Test
    fun noiseStepUpDoesNotLatchOpen() {
        val sim = Sim(VoxDetector())
        repeat(500) { sim.step(400f, ::noiseFrame) }
        repeat(1500) { sim.step(2000f, ::noiseFrame) } // 30 s at 5x
        // may have opened once, but must be closed again after the floor adapts
        assertTrue(sim.opens <= 1)
        assertFalse(sim.open)
    }
}

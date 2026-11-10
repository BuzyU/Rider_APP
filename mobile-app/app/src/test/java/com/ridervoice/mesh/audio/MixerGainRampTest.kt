package com.ridervoice.mesh.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerGainRampTest {

    @Test
    fun softLimiter_compressesSmoothlyAndMonotonically() {
        val jitterManager = MultiSourceJitterBufferManager(clock = { 10_000L })

        // 1. Linear region: for sum <= 0.7 * 32767 (approx 22936), response must be perfectly linear
        val linearSample = jitterManager.softLimitSample(15_000, 2)
        assertEquals(15_000.toShort(), linearSample)

        val linearSample2 = jitterManager.softLimitSample(20_000, 2)
        assertEquals(20_000.toShort(), linearSample2)

        // 2. Monotonic ramp across the compression knee:
        var prevOutput = linearSample2.toInt()
        for (sum in 21_000..65_534 step 1_000) {
            val out = jitterManager.softLimitSample(sum, 2).toInt()
            assertTrue("Output must be monotonically non-decreasing at sum=$sum (prev=$prevOutput, cur=$out)", out >= prevOutput)
            assertTrue("Output must never exceed Short.MAX_VALUE at sum=$sum", out <= Short.MAX_VALUE)
            prevOutput = out
        }

        // 3. Negative linear & compression region
        val negLinear = jitterManager.softLimitSample(-15_000, 2)
        assertEquals((-15_000).toShort(), negLinear)

        val negLarge = jitterManager.softLimitSample(-60_000, 2).toInt()
        assertTrue("Negative large sum must compress smoothly without underflow: $negLarge", negLarge in -32_768..-32_000)
    }
}


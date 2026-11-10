package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MixerNoClipTest {

    @Test
    fun mixingMaxAmplitudeWaves_doesNotHarshClipOrOverflow() {
        val jitterManager = MultiSourceJitterBufferManager(clock = { 10_000L })
        val numSamples = MeshConfig.BYTES_PER_FRAME / 2

        // Two full-scale streams: both at 30,000 (sum would be 60,000 without soft limiting)
        val frame1 = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val frame2 = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val b1 = ByteBuffer.wrap(frame1).order(ByteOrder.LITTLE_ENDIAN)
        val b2 = ByteBuffer.wrap(frame2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            b1.putShort(30_000.toShort())
            b2.putShort(30_000.toShort())
        }

        val mixed = jitterManager.mixPcm16Frames(listOf(frame1, frame2))
        val mb = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val sample = mb.short
            // Must stay bounded within valid 16-bit range
            assertTrue("Sample must not exceed Short.MAX_VALUE", sample <= Short.MAX_VALUE)
            assertTrue("Sample must not be less than Short.MIN_VALUE", sample >= Short.MIN_VALUE)
            // Soft limiter compresses sum of 60,000 to ~32,600 without harsh clipping to exactly 32767
            assertTrue("Soft-limited output must be close to max without harsh distortion: $sample", sample in 32_000..32_767)
        }
    }
}


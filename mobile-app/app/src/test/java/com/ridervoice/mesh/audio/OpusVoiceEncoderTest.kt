package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.codec.OpusVoiceDecoder
import com.ridervoice.mesh.audio.codec.OpusVoiceEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class OpusVoiceEncoderTest {

    @Test
    fun fecEnabledEncodeDecodeRoundTrip() {
        val encoder = OpusVoiceEncoder()
        val decoder = OpusVoiceDecoder()

        // 1kHz synthetic voice signal at 16kHz sample rate
        val tone = ShortArray(MeshConfig.SAMPLES_PER_FRAME) { i ->
            (sin(2.0 * Math.PI * 1000.0 * i / MeshConfig.SAMPLE_RATE) * 12000).toInt().toShort()
        }
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME)
        for (i in tone.indices) {
            pcm[i * 2] = (tone[i].toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((tone[i].toInt() shr 8) and 0xFF).toByte()
        }

        // Encode 3 frames to warm up encoder state and build in-band FEC
        val frame1 = encoder.encode(pcm)
        val frame2 = encoder.encode(pcm)
        val frame3 = encoder.encode(pcm)

        assertTrue("Frame 1 encoded size must be > 0", frame1.isNotEmpty())
        assertTrue("Frame 2 encoded size must be > 0", frame2.isNotEmpty())
        assertTrue("Frame 3 encoded size must be > 0", frame3.isNotEmpty())

        // Normal decode of first two frames
        val decoded1 = decoder.decode(frame1)
        assertEquals("Frame 1 decoded must be 640 bytes", MeshConfig.BYTES_PER_FRAME, decoded1.size)

        val decoded2 = decoder.decode(frame2)
        assertEquals("Frame 2 decoded must be 640 bytes", MeshConfig.BYTES_PER_FRAME, decoded2.size)

        // Simulate frame 3 lost: call decode(null) which triggers PLC with FEC
        val plcWithFec = decoder.decode(null)
        assertNotNull(plcWithFec)
        assertEquals("PLC with FEC must produce 640 bytes PCM", MeshConfig.BYTES_PER_FRAME, plcWithFec.size)
    }
}


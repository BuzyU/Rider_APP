package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.codec.OpusVoiceDecoder
import com.ridervoice.mesh.audio.codec.OpusVoiceEncoder
import com.ridervoice.mesh.audio.codec.PcmVoiceDecoder
import com.ridervoice.mesh.audio.codec.PcmVoiceEncoder
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sin

class OpusCodecTest {

    @Test
    fun opusSizeTest_everyFullFrameIs40Bytes_datagramOnV3Is147Bytes() {
        val encoder = OpusVoiceEncoder()
        val voicePcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x42 }
        val encoded = encoder.encode(voicePcm)

        assertEquals("At 16 kbit/s every full voice frame must be exactly 40 bytes", 40, encoded.size)

        // Inner GCM tag is 16 bytes
        val encryptedPayloadSize = encoded.size + MeshConfig.GCM_TAG_BYTES
        assertEquals("Encrypted payload must be 56 bytes", 56, encryptedPayloadSize)

        // Total UDP datagram on v3: header (75) + encrypted payload (56) + outer HMAC (16) = 147 bytes
        val totalDatagramBytes = MeshPacketCodec.HEADER_LEN + encryptedPayloadSize + MeshPacketCodec.TAG_LEN
        assertEquals("Total voice datagram on protocol v3 must be exactly 147 bytes", 147, totalDatagramBytes)
        assertEquals("MeshConfig.MAX_VOICE_DATAGRAM_BYTES must be 200", 200, MeshConfig.MAX_VOICE_DATAGRAM_BYTES)
        assertTrue(totalDatagramBytes <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)
    }

    @Test
    fun pcmCodecRoundTripTest_encodesAndDecodesFaithfully() {
        val encoder = PcmVoiceEncoder()
        val decoder = PcmVoiceDecoder()

        val originalPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { (it * 3).toByte() }
        val encoded = encoder.encode(originalPcm)
        assertEquals(MeshConfig.BYTES_PER_FRAME, encoded.size)

        val decoded = decoder.decode(encoded)
        assertEquals(MeshConfig.BYTES_PER_FRAME, decoded.size)
        assertTrue("Decoded PCM must equal original PCM", originalPcm.contentEquals(decoded))
    }

    @Test
    fun codecFormatRejectionTest_unsupportedFormatRejectedByDecoder() {
        val dummyKey = ByteArray(32) { 0x11 }
        val dummySession = "11111111-1111-1111-1111-111111111111"
        val dummySource = "22222222-2222-2222-2222-222222222222"

        val packetWithInvalidFormat = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = dummySession,
            sourceId = dummySource,
            sequence = 1,
            captureTimestamp = 100_000L,
            ttl = 5,
            hopCount = 0,
            previousHopId = dummySource,
            audioFormat = 99, // unsupported format
            epoch = 1,
            payload = ByteArray(40 + MeshConfig.GCM_TAG_BYTES)
        )
        val wire = MeshPacketCodec.encode(packetWithInvalidFormat, dummyKey)
        val res = MeshPacketCodec.decode(wire, dummyKey)
        assertTrue("Decode must be invalid for unsupported format 99", res is com.ridervoice.mesh.protocol.DecodeResult.Invalid)
        assertEquals(com.ridervoice.mesh.protocol.DecodeResult.Reason.BAD_FORMAT, (res as com.ridervoice.mesh.protocol.DecodeResult.Invalid).reason)
    }

    @Test
    fun opusPlcTest_oneMissingFrameReturns640BytesWithoutException() {
        val encoder = OpusVoiceEncoder()
        val decoder = OpusVoiceDecoder()

        // Feed two good frames to warm up decoder state
        val tone = ShortArray(MeshConfig.SAMPLES_PER_FRAME) { i -> (sin(2 * Math.PI * 1000.0 * i / 16000.0) * 10000).toInt().toShort() }
        val pcm = ShortArrayToBytes(tone)

        val frame1 = encoder.encode(pcm)
        decoder.decode(frame1)

        // Missing frame: decode(null) -> PLC
        val plcDecoded = decoder.decode(null)
        assertNotNull(plcDecoded)
        assertEquals("PLC must produce exactly 640 bytes PCM", MeshConfig.BYTES_PER_FRAME, plcDecoded.size)

        // Second missing frame: still valid PLC
        val plcDecoded2 = decoder.decode(null)
        assertNotNull(plcDecoded2)
        assertEquals("Second PLC frame must produce 640 bytes PCM", MeshConfig.BYTES_PER_FRAME, plcDecoded2.size)

        // Third missing frame: comfort silence (640 zeros)
        val silence = decoder.decode(null)
        assertEquals(MeshConfig.BYTES_PER_FRAME, silence.size)
        assertTrue("Third missing frame must produce silence", silence.all { it == 0.toByte() })
    }

    @Test
    fun opusRoundTripQualityTest_syntheticToneSnrAbove10dB() {
        val encoder = OpusVoiceEncoder()
        val decoder = OpusVoiceDecoder()

        val sampleRate = MeshConfig.SAMPLE_RATE
        val freq = 1000.0 // 1 kHz test tone within the 300-3400 Hz voice band
        val amplitude = 12000.0
        val numFrames = 25

        val originalShorts = mutableListOf<Short>()
        val decodedShorts = mutableListOf<Short>()

        for (f in 0 until numFrames) {
            val frameShorts = ShortArray(MeshConfig.SAMPLES_PER_FRAME) { i ->
                val totalSampleIdx = f * MeshConfig.SAMPLES_PER_FRAME + i
                (sin(2 * Math.PI * freq * totalSampleIdx / sampleRate) * amplitude).toInt().toShort()
            }
            frameShorts.forEach { originalShorts.add(it) }

            val pcm = ShortArrayToBytes(frameShorts)
            val encoded = encoder.encode(pcm)
            val decodedPcm = decoder.decode(encoded)
            val decShortArray = bytesToShortArray(decodedPcm)
            decShortArray.forEach { decodedShorts.add(it) }
        }

        // Align algorithmic delay by finding optimal lag between 0 and 150 samples
        val searchStart = 5 * MeshConfig.SAMPLES_PER_FRAME
        val searchEnd = (numFrames - 5) * MeshConfig.SAMPLES_PER_FRAME
        val length = searchEnd - searchStart

        var bestSnr = 0.0
        for (lag in 0..120) {
            var signalPower = 0.0
            var noisePower = 0.0
            for (i in 0 until length) {
                val orig = originalShorts[searchStart + i].toDouble()
                val dec = decodedShorts[searchStart + i + lag].toDouble()
                val noise = orig - dec
                signalPower += orig * orig
                noisePower += noise * noise
            }
            if (noisePower > 0.0) {
                val snr = 10.0 * log10(signalPower / noisePower)
                if (snr > bestSnr) {
                    bestSnr = snr
                }
            }
        }

        println("Opus 16kHz voice round-trip measured SNR: $bestSnr dB (threshold > 10 dB)")
        assertTrue("Opus round-trip SNR must exceed 10 dB on 300-3400 Hz band (measured $bestSnr dB)", bestSnr > 10.0)
    }

    private fun ShortArrayToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { bb.putShort(it) }
        return bytes
    }

    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = bb.short
        return shorts
    }
}

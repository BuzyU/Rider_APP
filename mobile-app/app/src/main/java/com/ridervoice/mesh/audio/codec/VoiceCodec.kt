package com.ridervoice.mesh.audio.codec

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.AudioFormatId
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface VoiceEncoder {
    val formatId: Int
    fun encode(pcm640: ByteArray): ByteArray /* may be 1 byte = DTX marker */
    fun reset()
    fun release()
}

interface VoiceDecoder {
    val formatId: Int
    fun decode(payload: ByteArray?): ByteArray /* null = conceal; returns 640 B */
    fun reset()
    fun release()
}

class PcmVoiceEncoder : VoiceEncoder {
    override val formatId: Int = AudioFormatId.PCM16_16K_MONO

    override fun encode(pcm640: ByteArray): ByteArray {
        return pcm640.copyOf()
    }

    override fun reset() {}
    override fun release() {}
}

class PcmVoiceDecoder : VoiceDecoder {
    override val formatId: Int = AudioFormatId.PCM16_16K_MONO
    private var lastPcm: ByteArray? = null
    private var consecutiveConcealed = 0

    override fun decode(payload: ByteArray?): ByteArray {
        if (payload == null) {
            consecutiveConcealed++
            val prev = lastPcm
            if (prev == null || consecutiveConcealed > 2) {
                return ByteArray(MeshConfig.BYTES_PER_FRAME)
            }
            val out = ByteArray(MeshConfig.BYTES_PER_FRAME)
            val factor = if (consecutiveConcealed == 1) 0.5f else 0.25f
            for (i in 0 until MeshConfig.SAMPLES_PER_FRAME) {
                val low = prev[i * 2].toInt() and 0xFF
                val high = prev[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort()
                val attenuated = (sample * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out[i * 2] = (attenuated.toInt() and 0xFF).toByte()
                out[i * 2 + 1] = ((attenuated.toInt() shr 8) and 0xFF).toByte()
            }
            lastPcm = out
            return out
        }
        consecutiveConcealed = 0
        if (payload.size <= 2) {
            return ByteArray(MeshConfig.BYTES_PER_FRAME)
        }
        val out = if (payload.size == MeshConfig.BYTES_PER_FRAME) payload.copyOf() else ByteArray(MeshConfig.BYTES_PER_FRAME)
        lastPcm = out
        return out
    }

    override fun reset() {
        lastPcm = null
        consecutiveConcealed = 0
    }

    override fun release() {
        lastPcm = null
        consecutiveConcealed = 0
    }
}

class OpusVoiceEncoder(
    bitrateBps: Int = MeshConfig.OPUS_BITRATE_BPS,
    complexity: Int = MeshConfig.OPUS_COMPLEXITY
) : VoiceEncoder {
    override val formatId: Int = AudioFormatId.OPUS_16K_MONO_20MS

    private var encoder = createEncoder(bitrateBps, complexity)
    private val shortBuf = ShortArray(MeshConfig.SAMPLES_PER_FRAME)
    private val outBuf = ByteArray(MeshConfig.MAX_OPUS_PAYLOAD_BYTES)

    private fun createEncoder(bitrate: Int, comp: Int): OpusEncoder {
        val enc = OpusEncoder(MeshConfig.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        enc.signalType = OpusSignal.OPUS_SIGNAL_VOICE
        enc.bitrate = bitrate
        enc.complexity = comp
        enc.useDTX = true
        enc.useInbandFEC = false
        enc.packetLossPercent = 0
        enc.useVBR = false
        return enc
    }

    override fun encode(pcm640: ByteArray): ByteArray {
        if (pcm640.size < MeshConfig.BYTES_PER_FRAME) {
            return ByteArray(1)
        }
        val bb = ByteBuffer.wrap(pcm640).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until MeshConfig.SAMPLES_PER_FRAME) {
            shortBuf[i] = bb.short
        }
        val encodedLen = encoder.encode(shortBuf, 0, MeshConfig.SAMPLES_PER_FRAME, outBuf, 0, outBuf.size)
        return if (encodedLen > 0) outBuf.copyOf(encodedLen) else ByteArray(1)
    }

    override fun reset() {
        encoder.resetState()
    }

    override fun release() {}
}

class OpusVoiceDecoder : VoiceDecoder {
    override val formatId: Int = AudioFormatId.OPUS_16K_MONO_20MS

    private var decoder = OpusDecoder(MeshConfig.SAMPLE_RATE, 1)
    private val outShorts = ShortArray(MeshConfig.SAMPLES_PER_FRAME)
    private var consecutiveConcealed = 0

    override fun decode(payload: ByteArray?): ByteArray {
        if (payload == null) {
            consecutiveConcealed++
            if (consecutiveConcealed > 2) {
                return ByteArray(MeshConfig.BYTES_PER_FRAME)
            }
            val decodedSamples = decoder.decode(null, 0, 0, outShorts, 0, MeshConfig.SAMPLES_PER_FRAME, false)
            return shortsToBytes(outShorts, decodedSamples)
        }
        consecutiveConcealed = 0
        if (payload.size <= 2) {
            return ByteArray(MeshConfig.BYTES_PER_FRAME)
        }
        val decodedSamples = decoder.decode(payload, 0, payload.size, outShorts, 0, MeshConfig.SAMPLES_PER_FRAME, false)
        return shortsToBytes(outShorts, decodedSamples)
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val out = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until minOf(count, MeshConfig.SAMPLES_PER_FRAME)) {
            bb.putShort(shorts[i])
        }
        return out
    }

    override fun reset() {
        decoder.resetState()
        consecutiveConcealed = 0
    }

    override fun release() {}
}

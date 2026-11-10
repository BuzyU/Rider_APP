package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Protocol v4 compact wire codec.
 *
 * Wire layout (17-byte header, all big-endian):
 * 0:   vtf (1 B)       version(3 bits)=4 | type(2 bits: 0=VOICE, 1=HELLO, 2=BYE) | fmt(3 bits: 0=none, 1=PCM, 2=OPUS16)
 * 1:   ttlHop (1 B)    ttl(4 bits) | hop(4 bits)            [MUTABLE - not authenticated]
 * 2:   srcShort (4 B)  first 4 bytes of origin deviceId
 * 6:   prevShort (4 B) first 4 bytes of transmitting deviceId [MUTABLE]
 * 10:  epoch (4 B)     32-bit random transport epoch
 * 14:  seq (3 B)       24-bit sequence counter (modulo 2^24)
 * 17:  ciphertext (N B) N = datagram length - 17 - 12
 * 17+N: tag (12 B)     AES-GCM tag, 96 bits
 */
object MeshPacketV4 {
    const val VERSION = 4
    const val HEADER_LEN = 17
    const val TAG_LEN = 12
    const val MIN_PACKET_LEN = HEADER_LEN + TAG_LEN // 29 bytes

    fun shortId(deviceId: String): Int {
        val u = UUID.fromString(deviceId)
        return (u.mostSignificantBits ushr 32).toInt()
    }

    fun encode(p: MeshPacket, payloadKey: ByteArray): ByteArray {
        val srcShort = if (p.sourceShortId != 0) p.sourceShortId else if (p.sourceHi != 0L) (p.sourceHi ushr 32).toInt() else shortId(p.sourceId)
        val prevShort = if (p.previousHopShortId != 0) p.previousHopShortId else if (p.prevHopHi != 0L) (p.prevHopHi ushr 32).toInt() else shortId(p.previousHopId)
        val fmt = if (p.type == MeshPacketType.VOICE) p.audioFormat else AudioFormatId.NONE

        val vtf = ((VERSION and 0x07) shl 5) or ((p.type.codeV4 and 0x03) shl 3) or (fmt and 0x07)
        val ttlHop = ((p.ttl and 0x0F) shl 4) or (p.hopCount and 0x0F)

        val sourceKey = PayloadCrypto.sourceKeySpecV4(payloadKey, srcShort, p.epoch)
        val nonce = PayloadCrypto.nonceV4(p.type.codeV4, p.sequence, p.epoch)
        val aad = PayloadCrypto.aadV4(vtf.toByte(), srcShort, p.epoch, p.sequence)

        val sealed = PayloadCrypto.sealV4(sourceKey, nonce, aad, p.payload)

        val out = ByteArray(HEADER_LEN + sealed.size)
        out[0] = vtf.toByte()
        out[1] = ttlHop.toByte()

        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.position(2)
        buf.putInt(srcShort)
        buf.putInt(prevShort)
        buf.putInt(p.epoch)
        buf.put((p.sequence ushr 16).toByte())
        buf.put((p.sequence ushr 8).toByte())
        buf.put(p.sequence.toByte())
        buf.put(sealed)

        return out
    }

    fun decode(data: ByteArray, payloadKey: ByteArray): DecodeResult {
        if (data.size < MIN_PACKET_LEN) {
            return DecodeResult.Invalid(DecodeResult.Reason.TOO_SHORT)
        }
        if (data.size > HEADER_LEN + MeshConfig.MAX_PAYLOAD_BYTES + TAG_LEN) {
            return DecodeResult.Invalid(DecodeResult.Reason.BAD_LENGTH)
        }
        val vtf = data[0].toInt() and 0xFF
        val version = (vtf ushr 5) and 0x07
        if (version != VERSION) {
            return DecodeResult.Invalid(DecodeResult.Reason.BAD_VERSION)
        }

        val typeCode = (vtf ushr 3) and 0x03
        val type = MeshPacketType.fromCodeV4(typeCode)
            ?: return DecodeResult.Invalid(DecodeResult.Reason.BAD_TYPE)

        val fmt = vtf and 0x07
        if (type == MeshPacketType.VOICE && fmt != AudioFormatId.PCM16_16K_MONO && fmt != AudioFormatId.OPUS_16K_MONO_20MS) {
            return DecodeResult.Invalid(DecodeResult.Reason.BAD_FORMAT)
        }
        val ttlHop = data[1].toInt() and 0xFF
        val ttl = (ttlHop ushr 4) and 0x0F
        val hop = ttlHop and 0x0F

        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(2)
        val srcShort = buf.int
        val prevShort = buf.int
        val epoch = buf.int
        val seq = ((data[14].toInt() and 0xFF) shl 16) or ((data[15].toInt() and 0xFF) shl 8) or (data[16].toInt() and 0xFF)

        val ciphertextAndTag = data.copyOfRange(HEADER_LEN, data.size)
        val sourceKey = PayloadCrypto.sourceKeySpecV4(payloadKey, srcShort, epoch)
        val nonce = PayloadCrypto.nonceV4(typeCode, seq, epoch)
        val aad = PayloadCrypto.aadV4(vtf.toByte(), srcShort, epoch, seq)

        val plain = PayloadCrypto.openV4(sourceKey, nonce, aad, ciphertextAndTag)
            ?: return DecodeResult.Invalid(DecodeResult.Reason.BAD_TAG)

        val packet = MeshPacket(
            type = type,
            sourceShortId = srcShort,
            previousHopShortId = prevShort,
            sourceHi = (srcShort.toLong() and 0xFFFFFFFFL) shl 32,
            prevHopHi = (prevShort.toLong() and 0xFFFFFFFFL) shl 32,
            sequence = seq,
            ttl = ttl,
            hopCount = hop,
            audioFormat = fmt,
            epoch = epoch,
            payload = plain
        )
        return DecodeResult.Ok(packet)
    }

    fun rewriteMutableFieldsForForward(datagram: ByteArray, newTtl: Int, newHop: Int, selfShort: Int): ByteArray {
        val copy = datagram.copyOf()
        copy[1] = (((newTtl and 0x0F) shl 4) or (newHop and 0x0F)).toByte()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)
        buf.position(6)
        buf.putInt(selfShort)
        return copy
    }
}


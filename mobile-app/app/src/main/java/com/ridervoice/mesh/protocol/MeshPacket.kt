package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class MeshPacketType(val code: Int) {
    HELLO(1), VOICE(2), BYE(3);

    companion object { fun fromCode(c: Int): MeshPacketType? = values().firstOrNull { it.code == c } }
}

/** Only raw 16 kHz mono PCM16 is defined. Any other value is rejected until a codec is negotiated. */
object AudioFormatId { const val PCM16_16K_MONO = 1 }

data class PacketIds(
    val sessionHi: Long,
    val sessionLo: Long,
    val sourceHi: Long,
    val sourceLo: Long
)

data class MeshPacket(
    val type: MeshPacketType,
    val sessionId: String,
    /** Original source. NEVER changes while forwarding. */
    val sourceId: String,
    val sequence: Int,
    val captureTimestamp: Long,
    /** Remaining number of times this packet may be forwarded. */
    val ttl: Int,
    val hopCount: Int,
    /** The device that transmitted this copy (== sourceId on hop 0). */
    val previousHopId: String,
    val audioFormat: Int = AudioFormatId.PCM16_16K_MONO,
    val epoch: Int = 0,
    val payload: ByteArray = ByteArray(0),
    val sessionHi: Long = 0L,
    val sessionLo: Long = 0L,
    val sourceHi: Long = 0L,
    val sourceLo: Long = 0L,
    val prevHopHi: Long = 0L,
    val prevHopLo: Long = 0L
) {
    val effectiveSessionHi: Long
        get() = if (sessionHi != 0L || sessionLo != 0L) sessionHi else MeshPacketCodec.uuidParts(sessionId).first
    val effectiveSessionLo: Long
        get() = if (sessionHi != 0L || sessionLo != 0L) sessionLo else MeshPacketCodec.uuidParts(sessionId).second

    val effectiveSourceHi: Long
        get() = if (sourceHi != 0L || sourceLo != 0L) sourceHi else MeshPacketCodec.uuidParts(sourceId).first
    val effectiveSourceLo: Long
        get() = if (sourceHi != 0L || sourceLo != 0L) sourceLo else MeshPacketCodec.uuidParts(sourceId).second

    val effectivePrevHopHi: Long
        get() = if (prevHopHi != 0L || prevHopLo != 0L) prevHopHi else MeshPacketCodec.uuidParts(previousHopId).first
    val effectivePrevHopLo: Long
        get() = if (prevHopHi != 0L || prevHopLo != 0L) prevHopLo else MeshPacketCodec.uuidParts(previousHopId).second

    val packetIds: PacketIds
        get() = PacketIds(effectiveSessionHi, effectiveSessionLo, effectiveSourceHi, effectiveSourceLo)

    /** Copy prepared for relaying by [selfId]; source, sequence, epoch and timestamp are preserved. */
    fun forwardedBy(selfId: String, selfHi: Long = 0L, selfLo: Long = 0L): MeshPacket {
        val (pHi, pLo) = if (selfHi != 0L || selfLo != 0L) Pair(selfHi, selfLo) else MeshPacketCodec.uuidParts(selfId)
        return copy(
            ttl = ttl - 1,
            hopCount = hopCount + 1,
            previousHopId = selfId,
            prevHopHi = pHi,
            prevHopLo = pLo
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MeshPacket && type == other.type && sessionId == other.sessionId &&
            sourceId == other.sourceId && sequence == other.sequence &&
            captureTimestamp == other.captureTimestamp && ttl == other.ttl &&
            hopCount == other.hopCount && previousHopId == other.previousHopId &&
            audioFormat == other.audioFormat && epoch == other.epoch &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * (31 * sourceId.hashCode() + sequence) + epoch) + type.hashCode()
}

sealed class DecodeResult {
    data class Ok(val packet: MeshPacket) : DecodeResult()
    data class Invalid(val reason: Reason) : DecodeResult()
    enum class Reason { TOO_SHORT, BAD_MAGIC, BAD_VERSION, BAD_TYPE, BAD_LENGTH, BAD_FORMAT, BAD_ID, BAD_TAG, MALFORMED }
}

/**
 * Binary big-endian codec (Protocol v3).
 *
 * magic(4) version(1) type(1) session(16) source(16) seq(4) ts(8) ttl(1) hop(1) prevHop(16)
 * format(1) epoch(4) payloadLen(2) payload tag(16)
 *
 * The outer tag is HMAC-SHA256 (truncated to 16 bytes) over everything before it, keyed from the session
 * authentication key. Inner payloads are encrypted with AES-256-GCM via PayloadCrypto.
 */
object MeshPacketCodec {
    const val MAGIC = 0x52564D31
    const val VERSION = 3
    const val TAG_LEN = 16
    const val HEADER_LEN = 4 + 1 + 1 + 16 + 16 + 4 + 8 + 1 + 1 + 16 + 1 + 4 + 2 // 75 bytes

    private val macThreadLocal: ThreadLocal<Mac> = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

    fun computeTag(data: ByteArray, offset: Int, length: Int, key: ByteArray): ByteArray {
        val mac = macThreadLocal.get()!!
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(data, offset, length)
        return mac.doFinal().copyOf(TAG_LEN)
    }

    fun computeTag(data: ByteArray, offset: Int, length: Int, keySpec: SecretKeySpec): ByteArray {
        val mac = macThreadLocal.get()!!
        mac.init(keySpec)
        mac.update(data, offset, length)
        return mac.doFinal().copyOf(TAG_LEN)
    }

    fun tag(body: ByteArray, key: ByteArray): ByteArray = computeTag(body, 0, body.size, key)

    fun encode(p: MeshPacket, key: ByteArray): ByteArray {
        require(p.payload.size <= MeshConfig.MAX_PAYLOAD_BYTES) { "payload too large" }
        require(p.ttl in 0..255 && p.hopCount in 0..255) { "ttl/hop out of range" }
        val buf = ByteBuffer.allocate(HEADER_LEN + p.payload.size + TAG_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC).put(VERSION.toByte()).put(p.type.code.toByte())
        buf.putLong(p.effectiveSessionHi).putLong(p.effectiveSessionLo)
        buf.putLong(p.effectiveSourceHi).putLong(p.effectiveSourceLo)
        buf.putInt(p.sequence).putLong(p.captureTimestamp)
        buf.put(p.ttl.toByte()).put(p.hopCount.toByte())
        buf.putLong(p.effectivePrevHopHi).putLong(p.effectivePrevHopLo)
        buf.put(p.audioFormat.toByte()).putInt(p.epoch).putShort(p.payload.size.toShort()).put(p.payload)
        val tagBytes = computeTag(buf.array(), 0, HEADER_LEN + p.payload.size, key)
        buf.put(tagBytes)
        return buf.array()
    }

    fun decode(data: ByteArray, key: ByteArray): DecodeResult {
        if (data.size < HEADER_LEN + TAG_LEN) return DecodeResult.Invalid(DecodeResult.Reason.TOO_SHORT)
        if (data.size > HEADER_LEN + MeshConfig.MAX_PAYLOAD_BYTES + TAG_LEN)
            return DecodeResult.Invalid(DecodeResult.Reason.BAD_LENGTH)
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (buf.int != MAGIC) return DecodeResult.Invalid(DecodeResult.Reason.BAD_MAGIC)
            if ((buf.get().toInt() and 0xFF) != VERSION) return DecodeResult.Invalid(DecodeResult.Reason.BAD_VERSION)
            val type = MeshPacketType.fromCode(buf.get().toInt() and 0xFF)
                ?: return DecodeResult.Invalid(DecodeResult.Reason.BAD_TYPE)
            val sHi = buf.long
            val sLo = buf.long
            val session = UUID(sHi, sLo).toString()
            val oHi = buf.long
            val oLo = buf.long
            val source = UUID(oHi, oLo).toString()
            val seq = buf.int
            val ts = buf.long
            val ttl = buf.get().toInt() and 0xFF
            val hop = buf.get().toInt() and 0xFF
            val pHi = buf.long
            val pLo = buf.long
            val prev = UUID(pHi, pLo).toString()
            val format = buf.get().toInt() and 0xFF
            val epoch = buf.int
            val len = buf.short.toInt() and 0xFFFF
            if (len > MeshConfig.MAX_PAYLOAD_BYTES || data.size != HEADER_LEN + len + TAG_LEN)
                return DecodeResult.Invalid(DecodeResult.Reason.BAD_LENGTH)
            // Verify the tag before trusting any content without copying arrays
            val expectedTag = computeTag(data, 0, HEADER_LEN + len, key)
            if (!constantTimeEquals(expectedTag, data, HEADER_LEN + len, TAG_LEN)) {
                return DecodeResult.Invalid(DecodeResult.Reason.BAD_TAG)
            }
            if (type == MeshPacketType.VOICE && format != AudioFormatId.PCM16_16K_MONO)
                return DecodeResult.Invalid(DecodeResult.Reason.BAD_FORMAT)
            val payload = ByteArray(len).also { System.arraycopy(data, HEADER_LEN, it, 0, len) }
            DecodeResult.Ok(
                MeshPacket(
                    type = type,
                    sessionId = session,
                    sourceId = source,
                    sequence = seq,
                    captureTimestamp = ts,
                    ttl = ttl,
                    hopCount = hop,
                    previousHopId = prev,
                    audioFormat = format,
                    epoch = epoch,
                    payload = payload,
                    sessionHi = sHi,
                    sessionLo = sLo,
                    sourceHi = oHi,
                    sourceLo = oLo,
                    prevHopHi = pHi,
                    prevHopLo = pLo
                )
            )
        } catch (e: Exception) {
            DecodeResult.Invalid(DecodeResult.Reason.MALFORMED)
        }
    }

    private fun constantTimeEquals(expected: ByteArray, data: ByteArray, offset: Int, len: Int): Boolean {
        if (expected.size != len || data.size < offset + len) return false
        var result = 0
        for (i in 0 until len) {
            result = result or (expected[i].toInt() xor data[offset + i].toInt())
        }
        return result == 0
    }

    fun uuidParts(id: String): Pair<Long, Long> {
        val u = UUID.fromString(id)
        return Pair(u.mostSignificantBits, u.leastSignificantBits)
    }

    fun uuidBytes(id: String): ByteArray {
        val u = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }

    private fun readUuid(buf: ByteBuffer): String = UUID(buf.long, buf.long).toString()
}

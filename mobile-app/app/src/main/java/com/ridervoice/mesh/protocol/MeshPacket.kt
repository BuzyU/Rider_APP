package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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
    val sourceLo: Long = 0L
) {
    val packetIds: PacketIds
        get() = if (sessionHi != 0L || sessionLo != 0L || sourceHi != 0L || sourceLo != 0L) {
            PacketIds(sessionHi, sessionLo, sourceHi, sourceLo)
        } else {
            val (sHi, sLo) = MeshPacketCodec.uuidParts(sessionId)
            val (oHi, oLo) = MeshPacketCodec.uuidParts(sourceId)
            PacketIds(sHi, sLo, oHi, oLo)
        }

    /** Copy prepared for relaying by [selfId]; source, sequence, epoch and timestamp are preserved. */
    fun forwardedBy(selfId: String): MeshPacket =
        copy(ttl = ttl - 1, hopCount = hopCount + 1, previousHopId = selfId)

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
    enum class Reason { TOO_SHORT, BAD_MAGIC, BAD_VERSION, BAD_TYPE, BAD_LENGTH, BAD_FORMAT, BAD_ID, BAD_TAG }
}

/**
 * Binary big-endian codec (Protocol v2).
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

    fun encode(p: MeshPacket, key: ByteArray): ByteArray {
        require(p.payload.size <= MeshConfig.MAX_PAYLOAD_BYTES) { "payload too large" }
        require(p.ttl in 0..255 && p.hopCount in 0..255) { "ttl/hop out of range" }
        val buf = ByteBuffer.allocate(HEADER_LEN + p.payload.size + TAG_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC).put(VERSION.toByte()).put(p.type.code.toByte())
        buf.put(uuidBytes(p.sessionId)).put(uuidBytes(p.sourceId))
        buf.putInt(p.sequence).putLong(p.captureTimestamp)
        buf.put(p.ttl.toByte()).put(p.hopCount.toByte()).put(uuidBytes(p.previousHopId))
        buf.put(p.audioFormat.toByte()).putInt(p.epoch).putShort(p.payload.size.toShort()).put(p.payload)
        val body = buf.array().copyOf(HEADER_LEN + p.payload.size)
        buf.put(tag(body, key))
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
        val prev = readUuid(buf)
        val format = buf.get().toInt() and 0xFF
        val epoch = buf.int
        val len = buf.short.toInt() and 0xFFFF
        if (len > MeshConfig.MAX_PAYLOAD_BYTES || data.size != HEADER_LEN + len + TAG_LEN)
            return DecodeResult.Invalid(DecodeResult.Reason.BAD_LENGTH)
        // Verify the tag before trusting any content.
        val body = data.copyOf(HEADER_LEN + len)
        val got = data.copyOfRange(HEADER_LEN + len, data.size)
        if (!MessageDigest.isEqual(tag(body, key), got)) return DecodeResult.Invalid(DecodeResult.Reason.BAD_TAG)
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
                    sourceLo = oLo
                )
            )
        } catch (e: Exception) {
            DecodeResult.Invalid(DecodeResult.Reason.TOO_SHORT)
        }
    }

    private fun tag(body: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(body).copyOf(TAG_LEN)
    }

    fun uuidParts(id: String): Pair<Long, Long> {
        val u = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            val buf = ByteBuffer.wrap(hash)
            return Pair(buf.long, buf.long)
        }
        return Pair(u.mostSignificantBits, u.leastSignificantBits)
    }

    fun uuidBytes(id: String): ByteArray {
        val (hi, lo) = uuidParts(id)
        return ByteBuffer.allocate(16).putLong(hi).putLong(lo).array()
    }

    private fun readUuid(buf: ByteBuffer): String = UUID(buf.long, buf.long).toString()
}

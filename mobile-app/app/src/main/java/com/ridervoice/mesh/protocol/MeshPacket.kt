package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class MeshPacketType(val code: Int, val codeV4: Int) {
    VOICE(2, 0),
    HELLO(1, 1),
    BYE(3, 2);

    companion object {
        fun fromCode(c: Int): MeshPacketType? = values().firstOrNull { it.code == c }
        fun fromCodeV4(c: Int): MeshPacketType? = values().firstOrNull { it.codeV4 == c }
    }
}

/** Only raw 16 kHz mono PCM16 is defined. Any other value is rejected until a codec is negotiated. */
object AudioFormatId {
    const val NONE = 0
    const val PCM16_16K_MONO = 1
    const val OPUS_16K_MONO_20MS = 2

    const val BITMASK_PCM = 1 shl 0
    const val BITMASK_OPUS = 1 shl 1
    const val BITMASK_ALL = BITMASK_PCM or BITMASK_OPUS
}

data class PacketIds(
    val sessionHi: Long,
    val sessionLo: Long,
    val sourceHi: Long,
    val sourceLo: Long
)

data class MeshPacket(
    val type: MeshPacketType,
    val sessionId: String = "",
    /** Original source. NEVER changes while forwarding. */
    val sourceId: String = "",
    val sequence: Int = 0,
    val captureTimestamp: Long = 0L,
    /** Remaining number of times this packet may be forwarded. */
    val ttl: Int = MeshConfig.DEFAULT_TTL,
    val hopCount: Int = 0,
    /** The device that transmitted this copy (== sourceId on hop 0). */
    val previousHopId: String = "",
    val audioFormat: Int = AudioFormatId.NONE,
    val epoch: Int = 0,
    val payload: ByteArray = ByteArray(0),
    val sessionHi: Long = 0L,
    val sessionLo: Long = 0L,
    val sourceHi: Long = 0L,
    val sourceLo: Long = 0L,
    val prevHopHi: Long = 0L,
    val prevHopLo: Long = 0L,
    val sourceShortId: Int = 0,
    val previousHopShortId: Int = 0
) {
    val effectiveSessionHi: Long
        get() = if (sessionHi != 0L || sessionLo != 0L) sessionHi else if (sessionId.isNotBlank()) MeshPacketCodec.uuidParts(sessionId).first else 0L
    val effectiveSessionLo: Long
        get() = if (sessionHi != 0L || sessionLo != 0L) sessionLo else if (sessionId.isNotBlank()) MeshPacketCodec.uuidParts(sessionId).second else 0L

    val effectiveSourceHi: Long
        get() = if (sourceHi != 0L || sourceLo != 0L) sourceHi else if (sourceId.isNotBlank()) MeshPacketCodec.uuidParts(sourceId).first else 0L
    val effectiveSourceLo: Long
        get() = if (sourceHi != 0L || sourceLo != 0L) sourceLo else if (sourceId.isNotBlank()) MeshPacketCodec.uuidParts(sourceId).second else 0L

    val effectivePrevHopHi: Long
        get() = if (prevHopHi != 0L || prevHopLo != 0L) prevHopHi else if (previousHopId.isNotBlank()) MeshPacketCodec.uuidParts(previousHopId).first else 0L
    val effectivePrevHopLo: Long
        get() = if (prevHopHi != 0L || prevHopLo != 0L) prevHopLo else if (previousHopId.isNotBlank()) MeshPacketCodec.uuidParts(previousHopId).second else 0L

    val effectiveSourceShortId: Int
        get() = if (sourceShortId != 0) sourceShortId else if (sourceId.isNotBlank()) (effectiveSourceHi ushr 32).toInt() else 0

    val effectivePrevHopShortId: Int
        get() = if (previousHopShortId != 0) previousHopShortId else if (previousHopId.isNotBlank()) (effectivePrevHopHi ushr 32).toInt() else 0

    val packetIds: PacketIds
        get() = PacketIds(effectiveSessionHi, effectiveSessionLo, effectiveSourceHi, effectiveSourceLo)

    /** Copy prepared for relaying by [selfId]; source, sequence, and epoch are preserved. */
    fun forwardedBy(selfId: String, selfHi: Long = 0L, selfLo: Long = 0L, selfShort: Int = 0): MeshPacket {
        val (pHi, pLo) = if (selfHi != 0L || selfLo != 0L) Pair(selfHi, selfLo) else MeshPacketCodec.uuidParts(selfId)
        val short = if (selfShort != 0) selfShort else (pHi ushr 32).toInt()
        return copy(
            ttl = ttl - 1,
            hopCount = hopCount + 1,
            previousHopId = selfId,
            prevHopHi = pHi,
            prevHopLo = pLo,
            previousHopShortId = short
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MeshPacket && type == other.type &&
            (sessionId == other.sessionId || sessionId.isBlank() || other.sessionId.isBlank()) &&
            (sourceId == other.sourceId || sourceId.isBlank() || other.sourceId.isBlank()) &&
            sequence == other.sequence &&
            ttl == other.ttl &&
            hopCount == other.hopCount &&
            (previousHopId == other.previousHopId || previousHopId.isBlank() || other.previousHopId.isBlank()) &&
            audioFormat == other.audioFormat && epoch == other.epoch &&
            effectiveSourceShortId == other.effectiveSourceShortId &&
            effectivePrevHopShortId == other.effectivePrevHopShortId &&
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
 * Protocol v4 codec.
 *
 * Header: 17 bytes.
 * Auth: 12-byte AES-GCM tag.
 */
object MeshPacketCodec {
    const val MAGIC = 0x52564D31
    const val VERSION = 4
    const val TAG_LEN = 12
    const val HEADER_LEN = 17

    private val macThreadLocal: ThreadLocal<Mac> = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

    fun shortId(deviceId: String): Int = MeshPacketV4.shortId(deviceId)

    fun computeTag(data: ByteArray, offset: Int, length: Int, key: ByteArray): ByteArray {
        val mac = macThreadLocal.get()!!
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(data, offset, length)
        return mac.doFinal().copyOf(16)
    }

    fun computeTag(data: ByteArray, offset: Int, length: Int, keySpec: SecretKeySpec): ByteArray {
        val mac = macThreadLocal.get()!!
        mac.init(keySpec)
        mac.update(data, offset, length)
        return mac.doFinal().copyOf(16)
    }

    fun tag(body: ByteArray, key: ByteArray): ByteArray = computeTag(body, 0, body.size, key)

    fun encode(p: MeshPacket, key: ByteArray): ByteArray = MeshPacketV4.encode(p, key)

    fun decode(data: ByteArray, key: ByteArray): DecodeResult = MeshPacketV4.decode(data, key)

    fun uuidBytes(uuidStr: String): ByteArray {
        val u = UUID.fromString(uuidStr)
        val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        b.putLong(u.mostSignificantBits)
        b.putLong(u.leastSignificantBits)
        return b.array()
    }

    fun uuidParts(uuidStr: String): Pair<Long, Long> {
        val u = UUID.fromString(uuidStr)
        return Pair(u.mostSignificantBits, u.leastSignificantBits)
    }
}

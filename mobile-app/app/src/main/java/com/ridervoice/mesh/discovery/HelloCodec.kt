package com.ridervoice.mesh.discovery

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * HELLO payload: the sender's display name, its graph version and its direct neighbor ids.
 * Optionally carries an 8-byte online identity tag [0xA1][0x08][tag] when the sender is in an online ride.
 * HELLOs are flooded with a small TTL, so every device learns each other device's adjacency.
 */
data class HelloPayload(
    val name: String,
    val graphVersion: Int,
    val neighborIds: List<String>,
    val onlineIdTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloPayload) return false
        if (name != other.name) return false
        if (graphVersion != other.graphVersion) return false
        if (neighborIds != other.neighborIds) return false
        if (onlineIdTag != null) {
            if (other.onlineIdTag == null) return false
            if (!onlineIdTag.contentEquals(other.onlineIdTag)) return false
        } else if (other.onlineIdTag != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + graphVersion
        result = 31 * result + neighborIds.hashCode()
        result = 31 * result + (onlineIdTag?.contentHashCode() ?: 0)
        return result
    }
}

object HelloCodec {
    const val TAG_TYPE: Byte = 0xA1.toByte()
    const val TAG_LEN: Byte = 0x08.toByte()

    fun encode(p: HelloPayload): ByteArray {
        val nameBytes = p.name.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 96) it.copyOf(96) else it }
        val ids = p.neighborIds.take(MeshConfig.MAX_NEIGHBORS_IN_HELLO)
        val tagBytes = p.onlineIdTag
        require(tagBytes == null || tagBytes.size == 8) { "onlineIdTag must be 8 bytes when present" }
        val extraLen = if (tagBytes != null) 10 else 0
        val buf = ByteBuffer.allocate(1 + nameBytes.size + 4 + 1 + ids.size * 16 + extraLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(nameBytes.size.toByte()).put(nameBytes).putInt(p.graphVersion).put(ids.size.toByte())
        ids.forEach { buf.put(MeshPacketCodec.uuidBytes(it)) }
        if (tagBytes != null) {
            buf.put(TAG_TYPE).put(TAG_LEN).put(tagBytes)
        }
        return buf.array()
    }

    /** Returns null for any malformed payload. */
    fun decode(data: ByteArray): HelloPayload? = runCatching {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val nlen = buf.get().toInt() and 0xFF
        if (nlen > 96 || buf.remaining() < nlen + 5) return null
        val nb = ByteArray(nlen); buf.get(nb)
        val version = buf.int
        val count = buf.get().toInt() and 0xFF
        if (count > MeshConfig.MAX_NEIGHBORS_IN_HELLO || buf.remaining() < count * 16) return null
        val ids = List(count) { UUID(buf.long, buf.long).toString() }

        val onlineTag = when (buf.remaining()) {
            0 -> null
            10 -> {
                val t = buf.get()
                val l = buf.get()
                if (t != TAG_TYPE || l != TAG_LEN) return null
                val tag = ByteArray(8)
                buf.get(tag)
                tag
            }
            else -> return null // any other trailing bytes -> reject
        }

        HelloPayload(String(nb, StandardCharsets.UTF_8), version, ids, onlineTag)
    }.getOrNull()
}


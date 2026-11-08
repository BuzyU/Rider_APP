package com.ridervoice.mesh.discovery

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.PayloadCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * HELLO payload: the sender's display name, its graph version and its direct neighbor ids.
 * Optionally carries an 8-byte online identity tag [0xA1][0x08][tag] (represented as 16 hex chars)
 * when the sender is in an online ride.
 * HELLOs are flooded with a small TTL, so every device learns each other device's adjacency.
 */
data class HelloPayload(
    val name: String,
    val graphVersion: Int,
    val neighborIds: List<String>,
    val onlineIdTag: String? = null
)

object HelloCodec {
    const val TAG_TYPE: Byte = 0xA1.toByte()
    const val TAG_LEN: Byte = 0x08.toByte()

    fun encode(p: HelloPayload): ByteArray {
        val nameBytes = p.name.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 96) it.copyOf(96) else it }
        val ids = p.neighborIds.take(MeshConfig.MAX_NEIGHBORS_IN_HELLO)
        val tagHex = p.onlineIdTag
        val tagBytes = tagHex?.let {
            PayloadCrypto.hexToBytes(it)
        }
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
                PayloadCrypto.bytesToHex(tag)
            }
            else -> return null // any other trailing bytes -> reject
        }

        HelloPayload(String(nb, StandardCharsets.UTF_8), version, ids, onlineTag)
    }.getOrNull()
}


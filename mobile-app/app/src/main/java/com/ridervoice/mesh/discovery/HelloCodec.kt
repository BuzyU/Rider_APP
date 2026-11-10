package com.ridervoice.mesh.discovery

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.PayloadCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * HELLO payload: the sender's full 16-byte UUID device ID, display name, graph version,
 * and direct neighbor IDs.
 * Optionally carries an 8-byte online identity tag [0xA1][0x08][tag] (represented as 16 hex chars)
 * when the sender is in an online ride, and a 4-byte supported audio formats bitmask [0x02][0x04][mask].
 * HELLOs are flooded with a small TTL, so every device learns each other device's adjacency and capabilities.
 */
data class HelloPayload(
    val deviceId: String = UUID.randomUUID().toString(),
    val name: String,
    val graphVersion: Int,
    val neighborIds: List<String>,
    val onlineIdTag: String? = null,
    val supportedFormats: Int? = AudioFormatId.BITMASK_ALL
) {
    constructor(
        name: String,
        graphVersion: Int,
        neighborIds: List<String>,
        onlineIdTag: String? = null,
        supportedFormats: Int? = AudioFormatId.BITMASK_ALL
    ) : this(
        deviceId = UUID.randomUUID().toString(),
        name = name,
        graphVersion = graphVersion,
        neighborIds = neighborIds,
        onlineIdTag = onlineIdTag,
        supportedFormats = supportedFormats
    )
}

object HelloCodec {
    const val TAG_TYPE: Byte = 0xA1.toByte()
    const val TAG_LEN: Byte = 0x08.toByte()
    const val TAG_SUPPORTED_FORMATS: Byte = 0x02.toByte()
    const val TAG_SUPPORTED_FORMATS_LEN: Byte = 0x04.toByte()

    fun encode(p: HelloPayload): ByteArray {
        val nameBytes = p.name.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 96) it.copyOf(96) else it }
        val ids = p.neighborIds.take(MeshConfig.MAX_NEIGHBORS_IN_HELLO)
        val tagHex = p.onlineIdTag
        val tagBytes = tagHex?.let {
            PayloadCrypto.hexToBytes(it)
        }
        val formats = p.supportedFormats
        var extraLen = 0
        if (tagBytes != null) extraLen += 2 + 8
        if (formats != null) extraLen += 2 + 4

        val buf = ByteBuffer.allocate(16 + 1 + nameBytes.size + 4 + 1 + ids.size * 16 + extraLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(MeshPacketCodec.uuidBytes(p.deviceId))
        buf.put(nameBytes.size.toByte()).put(nameBytes).putInt(p.graphVersion).put(ids.size.toByte())
        ids.forEach { buf.put(MeshPacketCodec.uuidBytes(it)) }
        if (tagBytes != null) {
            buf.put(TAG_TYPE).put(TAG_LEN).put(tagBytes)
        }
        if (formats != null) {
            buf.put(TAG_SUPPORTED_FORMATS).put(TAG_SUPPORTED_FORMATS_LEN).putInt(formats)
        }
        return buf.array()
    }

    /** Returns null for any malformed payload or unexpected trailing garbage. */
    fun decode(data: ByteArray): HelloPayload? = runCatching {
        if (data.size < 16 + 1 + 4 + 1) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val deviceId = UUID(buf.long, buf.long).toString()
        val nlen = buf.get().toInt() and 0xFF
        if (nlen > 96 || buf.remaining() < nlen + 5) return null
        val nb = ByteArray(nlen); buf.get(nb)
        val version = buf.int
        val count = buf.get().toInt() and 0xFF
        if (count > MeshConfig.MAX_NEIGHBORS_IN_HELLO || buf.remaining() < count * 16) return null
        val ids = List(count) { UUID(buf.long, buf.long).toString() }

        var onlineTag: String? = null
        var formats: Int? = null

        while (buf.hasRemaining()) {
            if (buf.remaining() < 2) return null
            val t = buf.get()
            val l = buf.get().toInt() and 0xFF
            if (buf.remaining() < l) return null
            when {
                t == TAG_TYPE && l == (TAG_LEN.toInt() and 0xFF) -> {
                    if (onlineTag != null) return null
                    val tag = ByteArray(8)
                    buf.get(tag)
                    onlineTag = PayloadCrypto.bytesToHex(tag)
                }
                t == TAG_SUPPORTED_FORMATS && l == (TAG_SUPPORTED_FORMATS_LEN.toInt() and 0xFF) -> {
                    if (formats != null) return null
                    formats = buf.int
                }
                else -> return null
            }
        }

        HelloPayload(deviceId, String(nb, StandardCharsets.UTF_8), version, ids, onlineTag, formats)
    }.getOrNull()
}

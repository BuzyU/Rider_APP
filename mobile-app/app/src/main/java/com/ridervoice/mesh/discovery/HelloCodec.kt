package com.ridervoice.mesh.discovery

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * HELLO payload: the sender's display name, its graph version and its direct neighbor ids.
 * HELLOs are flooded with a small TTL, so every device learns each other device's adjacency.
 */
data class HelloPayload(val name: String, val graphVersion: Int, val neighborIds: List<String>)

object HelloCodec {
    fun encode(p: HelloPayload): ByteArray {
        val nameBytes = p.name.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 96) it.copyOf(96) else it }
        val ids = p.neighborIds.take(MeshConfig.MAX_NEIGHBORS_IN_HELLO)
        val buf = ByteBuffer.allocate(1 + nameBytes.size + 4 + 1 + ids.size * 16).order(ByteOrder.BIG_ENDIAN)
        buf.put(nameBytes.size.toByte()).put(nameBytes).putInt(p.graphVersion).put(ids.size.toByte())
        ids.forEach { buf.put(MeshPacketCodec.uuidBytes(it)) }
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
        if (count > MeshConfig.MAX_NEIGHBORS_IN_HELLO || buf.remaining() != count * 16) return null
        val ids = List(count) { UUID(buf.long, buf.long).toString() }
        HelloPayload(String(nb, StandardCharsets.UTF_8), version, ids)
    }.getOrNull()
}


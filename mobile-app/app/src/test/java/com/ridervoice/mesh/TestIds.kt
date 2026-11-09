package com.ridervoice.mesh

import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.PacketIds
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Deterministic UUID generator for tests, replacing ad-hoc non-UUID test strings.
 */
object TestIds {
    fun uuid(name: String): String =
        UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()

    fun packetIds(session: String, source: String): PacketIds {
        val s = if (isValidUuid(session)) session else uuid(session)
        val src = if (isValidUuid(source)) source else uuid(source)
        val (sHi, sLo) = MeshPacketCodec.uuidParts(s)
        val (oHi, oLo) = MeshPacketCodec.uuidParts(src)
        return PacketIds(sHi, sLo, oHi, oLo)
    }

    private fun isValidUuid(s: String): Boolean = try {
        UUID.fromString(s)
        true
    } catch (_: Exception) {
        false
    }
}

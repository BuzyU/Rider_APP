package com.ridervoice.mesh.audit

import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PacketIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DuplicateCacheNoStringParsingTest {

    @Test
    fun packetOverloadsAreTheOnlyPublicApi() {
        val publicMethods = DuplicatePacketCache::class.java.methods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.declaringClass == DuplicatePacketCache::class.java }

        for (m in publicMethods) {
            val paramTypes = m.parameterTypes
            assertFalse(
                "DuplicatePacketCache must expose no public methods taking String IDs: ${m.name}",
                paramTypes.contains(String::class.java)
            )
        }

        val cache = DuplicatePacketCache(clock = { 1000L })
        val ids = PacketIds(1L, 2L, 3L, 4L)
        assertTrue("markIfNew accepts PacketIds", cache.markIfNew(ids, epoch = 1, kind = 0, seq = 1))
        assertTrue("isDuplicate accepts PacketIds", cache.isDuplicate(ids, epoch = 1, kind = 0, seq = 1))
        assertFalse("Second markIfNew is not new", cache.markIfNew(ids, epoch = 1, kind = 0, seq = 1))
    }

    @Test
    fun decodedMeshPacketCarriesParsedLongsDirectly() {
        val sId = UUID.randomUUID().toString()
        val srcId = UUID.randomUUID().toString()
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sId,
            sourceId = srcId,
            sequence = 42,
            captureTimestamp = 123456789L,
            ttl = 3,
            hopCount = 0,
            previousHopId = srcId,
            epoch = 1,
            payload = ByteArray(32)
        )
        val key = ByteArray(32) { 7 }
        val encoded = MeshPacketCodec.encode(packet, key)

        val decoded = (MeshPacketCodec.decode(encoded, key) as com.ridervoice.mesh.protocol.DecodeResult.Ok).packet

        val expectedSessionParts = MeshPacketCodec.uuidParts(sId)
        val expectedSourceParts = MeshPacketCodec.uuidParts(srcId)

        assertEquals(expectedSessionParts.first, decoded.sessionHi)
        assertEquals(expectedSessionParts.second, decoded.sessionLo)
        assertEquals(expectedSourceParts.first, decoded.sourceHi)
        assertEquals(expectedSourceParts.second, decoded.sourceLo)

        val ids = decoded.packetIds
        assertEquals(expectedSessionParts.first, ids.sessionHi)
        assertEquals(expectedSessionParts.second, ids.sessionLo)
        assertEquals(expectedSourceParts.first, ids.sourceHi)
        assertEquals(expectedSourceParts.second, ids.sourceLo)
    }
}

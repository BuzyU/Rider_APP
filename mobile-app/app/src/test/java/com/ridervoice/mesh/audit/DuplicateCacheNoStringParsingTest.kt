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
import java.util.concurrent.atomic.AtomicInteger

class DuplicateCacheNoStringParsingTest {

    @Test
    fun packetBasedOverloadNeverCallsUuidParser() {
        val parserCount = AtomicInteger(0)
        val cache = DuplicatePacketCache(
            clock = { 1000L },
            uuidParser = {
                parserCount.incrementAndGet()
                MeshPacketCodec.uuidParts(it)
            }
        )

        val ids = PacketIds(
            sessionHi = 0x1234567890ABCDEFL,
            sessionLo = 0x0FEDCBA098765432L,
            sourceHi = 0x1111222233334444L,
            sourceLo = 0x5555666677778888L
        )

        // 1. markIfNew with PacketIds
        val isNew1 = cache.markIfNew(ids, epoch = 1, kind = 0, seq = 10)
        assertTrue("Packet must be recorded as new", isNew1)
        assertEquals("UUID parser must never be called by PacketIds overload", 0, parserCount.get())

        // 2. isDuplicate with PacketIds
        val isDup = cache.isDuplicate(ids, epoch = 1, kind = 0, seq = 10)
        assertTrue("Packet must be duplicate", isDup)
        assertEquals("UUID parser must never be called by isDuplicate PacketIds overload", 0, parserCount.get())

        // 3. markIfNew duplicate
        val isNew2 = cache.markIfNew(ids, epoch = 1, kind = 0, seq = 10)
        assertFalse("Duplicate packet must return false", isNew2)
        assertEquals("UUID parser must never be called on duplicate detection", 0, parserCount.get())

        // 4. Primitive long overload
        val isNew3 = cache.markIfNew(
            ids.sessionHi, ids.sessionLo,
            ids.sourceHi, ids.sourceLo,
            sequence = 20, kind = 0, epoch = 1
        )
        assertTrue("New packet with primitive longs must succeed", isNew3)
        assertEquals("UUID parser must never be called by primitive longs overload", 0, parserCount.get())

        // 5. Primitive long isDuplicate
        assertTrue(
            "Primitive long duplicate check must match",
            cache.isDuplicate(ids.sessionHi, ids.sessionLo, ids.sourceHi, ids.sourceLo, sequence = 20, kind = 0, epoch = 1)
        )
        assertEquals("UUID parser must never be called by primitive isDuplicate", 0, parserCount.get())

        // 6. Legacy String overload DOES invoke the parser seam
        val sessionStr = UUID(ids.sessionHi, ids.sessionLo).toString()
        val sourceStr = UUID(ids.sourceHi, ids.sourceLo).toString()
        val legacyDup = cache.isDuplicate(sessionStr, sourceStr, sequence = 10, kind = 0, epoch = 1)
        assertTrue("Legacy string overload identifies duplicate", legacyDup)
        assertEquals("Legacy string overload must invoke parser seam for both IDs", 2, parserCount.get())
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

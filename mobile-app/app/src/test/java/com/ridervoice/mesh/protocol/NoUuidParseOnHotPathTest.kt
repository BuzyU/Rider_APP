package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoUuidParseOnHotPathTest {

    @Test
    fun encodeAndAad_withLongIds_doesNotInvokeUuidFromString() {
        val authKey = ByteArray(32) { 0x42 }
        val payload = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        // Notice: sessionId, sourceId, previousHopId are purposely non-UUID strings.
        // If UUID.fromString was invoked, it would throw IllegalArgumentException!
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = "non-uuid-session-string",
            sourceId = "non-uuid-source-string",
            sequence = 123,
            captureTimestamp = 456789L,
            ttl = 5,
            hopCount = 0,
            previousHopId = "non-uuid-prevhop-string",
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = payload,
            sessionHi = 0x0123456789abcdefL,
            sessionLo = 0x0fedcba987654321L,
            sourceHi = 0x1122334455667788L,
            sourceLo = 0x1234567890abcdefL,
            prevHopHi = 0x1122334455667788L,
            prevHopLo = 0x1234567890abcdefL
        )

        // 1. encode must succeed without throwing IllegalArgumentException
        val wire = MeshPacketCodec.encode(packet, authKey)
        assertTrue("Wire format must be encoded", wire.isNotEmpty())

        // 2. decode must succeed and unpack the hi/lo Longs directly
        val decoded = MeshPacketCodec.decode(wire, authKey)
        assertTrue("Decode must succeed", decoded is DecodeResult.Ok)
        val decP = (decoded as DecodeResult.Ok).packet
        assertEquals(0x0123456789abcdefL, decP.sessionHi)
        assertEquals(0x0fedcba987654321L, decP.sessionLo)
        assertEquals(0x1122334455667788L, decP.sourceHi)
        assertEquals(0x1234567890abcdefL, decP.sourceLo)
        assertEquals(0x1122334455667788L, decP.prevHopHi)
        assertEquals(0x1234567890abcdefL, decP.prevHopLo)

        // 3. aad with Longs must succeed without throwing
        val aad = PayloadCrypto.aad(
            sessionHi = decP.effectiveSessionHi,
            sessionLo = decP.effectiveSessionLo,
            sourceHi = decP.effectiveSourceHi,
            sourceLo = decP.effectiveSourceLo,
            typeCode = decP.type.code,
            epoch = decP.epoch,
            sequence = decP.sequence,
            captureTimestamp = decP.captureTimestamp,
            audioFormat = decP.audioFormat
        )
        assertEquals(16 + 16 + 1 + 4 + 4 + 8 + 1, aad.size)
    }
}


package com.ridervoice.mesh.audit

import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NonUuidIdRejectedTest {

    @Test(expected = IllegalArgumentException::class)
    fun uuidParts_withNonUuidString_throwsIllegalArgumentException() {
        MeshPacketCodec.uuidParts("A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_withNonUuidSourceId_throwsIllegalArgumentException() {
        val validSession = UUID.randomUUID().toString()
        val invalidSource = "not-a-uuid"
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = validSession,
            sourceId = invalidSource,
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 3,
            hopCount = 0,
            previousHopId = invalidSource,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 0,
            payload = ByteArray(32)
        )
        val key = ByteArray(32) { 1 }
        MeshPacketCodec.encode(packet, key)
    }

    @Test
    fun roundTrip_withValidUuidIds_unchanged() {
        val validSession = UUID.randomUUID().toString()
        val validSource = UUID.randomUUID().toString()
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = validSession,
            sourceId = validSource,
            sequence = 42,
            captureTimestamp = 123456789L,
            ttl = 3,
            hopCount = 0,
            previousHopId = validSource,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = ByteArray(320) { it.toByte() }
        )
        val key = ByteArray(32) { 5 }
        val encoded = MeshPacketCodec.encode(packet, key)
        val decoded = (MeshPacketCodec.decode(encoded, key) as DecodeResult.Ok).packet

        assertEquals(validSession, decoded.sessionId)
        assertEquals(validSource, decoded.sourceId)
        assertEquals(42, decoded.sequence)
        assertEquals(1, decoded.epoch)
        assertTrue(packet.payload.contentEquals(decoded.payload))
    }
}


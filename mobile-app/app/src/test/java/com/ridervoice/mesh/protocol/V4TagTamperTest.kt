package com.ridervoice.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V4TagTamperTest {

    private val payloadKey = ByteArray(32) { (it * 5 + 3).toByte() }
    private val selfId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun flippingImmutableFieldsOrCiphertextOrTag_rejectsPacket() {
        val plain = ByteArray(40) { (it + 1).toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 100,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 1234,
            payload = plain
        )
        val validWire = MeshPacketV4.encode(packet, payloadKey)
        assertTrue(MeshPacketV4.decode(validWire, payloadKey) is DecodeResult.Ok)

        // 0: vtf (immutable)
        val tamperedVtf = validWire.copyOf()
        tamperedVtf[0] = (tamperedVtf[0].toInt() xor 0x01).toByte()
        assertTrue("Tampered vtf must be rejected", MeshPacketV4.decode(tamperedVtf, payloadKey) is DecodeResult.Invalid)

        // 2..5: srcShort (immutable)
        for (offset in 2..5) {
            val tampered = validWire.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 0x42).toByte()
            assertTrue("Tampered srcShort byte at $offset must be rejected", MeshPacketV4.decode(tampered, payloadKey) is DecodeResult.Invalid)
        }

        // 10..13: epoch (immutable)
        for (offset in 10..13) {
            val tampered = validWire.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 0x55).toByte()
            assertTrue("Tampered epoch byte at $offset must be rejected", MeshPacketV4.decode(tampered, payloadKey) is DecodeResult.Invalid)
        }

        // 14..16: seq (immutable)
        for (offset in 14..16) {
            val tampered = validWire.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 0xAA.toInt()).toByte()
            assertTrue("Tampered seq byte at $offset must be rejected", MeshPacketV4.decode(tampered, payloadKey) is DecodeResult.Invalid)
        }

        // 17..validWire.size - 1: ciphertext and tag
        for (offset in 17 until validWire.size) {
            val tampered = validWire.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 0x77).toByte()
            assertTrue("Tampered payload/tag byte at $offset must be rejected", MeshPacketV4.decode(tampered, payloadKey) is DecodeResult.Invalid)
        }
    }

    @Test
    fun flippingMutableFields_keepsPacketAuthentic() {
        val plain = ByteArray(40) { (it + 1).toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 100,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 1234,
            payload = plain
        )
        val validWire = MeshPacketV4.encode(packet, payloadKey)

        // 1: ttlHop (mutable)
        val modifiedTtlHop = validWire.copyOf()
        modifiedTtlHop[1] = (((4 shl 4) or 1)).toByte() // Decrement TTL, increment hop
        val decodedTtl = MeshPacketV4.decode(modifiedTtlHop, payloadKey)
        assertTrue("Modified ttlHop must still decode and authenticate successfully", decodedTtl is DecodeResult.Ok)
        assertEquals(4, (decodedTtl as DecodeResult.Ok).packet.ttl)
        assertEquals(1, decodedTtl.packet.hopCount)

        // 6..9: prevShort (mutable)
        val modifiedPrevShort = validWire.copyOf()
        modifiedPrevShort[6] = 0x12
        modifiedPrevShort[7] = 0x34
        modifiedPrevShort[8] = 0x56
        modifiedPrevShort[9] = 0x78
        val decodedPrev = MeshPacketV4.decode(modifiedPrevShort, payloadKey)
        assertTrue("Modified prevShort must still decode and authenticate successfully", decodedPrev is DecodeResult.Ok)
        assertEquals(0x12345678, (decodedPrev as DecodeResult.Ok).packet.previousHopShortId)
    }
}

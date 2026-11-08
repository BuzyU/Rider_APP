package com.ridervoice.mesh.audit

import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class HelloOnlineTagCodecTest {

    @Test
    fun roundTripWithoutTag() {
        val u1 = UUID.randomUUID().toString()
        val u2 = UUID.randomUUID().toString()
        val payload = HelloPayload("Alice", 3, listOf(u1, u2), null)

        val encoded = HelloCodec.encode(payload)
        val decoded = HelloCodec.decode(encoded)

        assertNotNull("Payload must decode successfully", decoded)
        assertEquals("Alice", decoded!!.name)
        assertEquals(3, decoded.graphVersion)
        assertEquals(listOf(u1, u2), decoded.neighborIds)
        assertNull("onlineIdTag must be null", decoded.onlineIdTag)
        assertEquals(payload, decoded)
    }

    @Test
    fun roundTripWithValidTag() {
        val u1 = UUID.randomUUID().toString()
        val tag = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val payload = HelloPayload("Bob", 5, listOf(u1), tag)

        val encoded = HelloCodec.encode(payload)
        val decoded = HelloCodec.decode(encoded)

        assertNotNull("Payload with tag must decode successfully", decoded)
        assertEquals("Bob", decoded!!.name)
        assertEquals(5, decoded.graphVersion)
        assertEquals(listOf(u1), decoded.neighborIds)
        assertNotNull("onlineIdTag must not be null", decoded.onlineIdTag)
        assertArrayEquals(tag, decoded.onlineIdTag)
        assertEquals(payload, decoded)
    }

    @Test
    fun tagWrongLengthRejectedAtEncode() {
        val wrongTag = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07) // 7 bytes
        val payload = HelloPayload("Charlie", 1, emptyList(), wrongTag)
        try {
            HelloCodec.encode(payload)
            fail("Expected IllegalArgumentException for 7-byte tag")
        } catch (e: IllegalArgumentException) {
            assertTrue("Exception message mentions 8 bytes", e.message?.contains("8 bytes") == true)
        }
    }

    @Test
    fun trailingGarbageRejected() {
        val payload = HelloPayload("Dave", 1, emptyList(), null)
        val encoded = HelloCodec.encode(payload)

        // Append 1 trailing byte
        val corrupted1 = encoded + byteArrayOf(0x99.toByte())
        assertNull("Payload with odd trailing byte must be rejected", HelloCodec.decode(corrupted1))

        // Append 10 bytes with wrong tag type
        val wrongTypeTrailer = encoded + byteArrayOf(0xB1.toByte(), 0x08.toByte()) + ByteArray(8)
        assertNull("Payload with wrong tag type must be rejected", HelloCodec.decode(wrongTypeTrailer))

        // Append 10 bytes with wrong tag length
        val wrongLengthTrailer = encoded + byteArrayOf(0xA1.toByte(), 0x07.toByte()) + ByteArray(8)
        assertNull("Payload with wrong tag length must be rejected", HelloCodec.decode(wrongLengthTrailer))

        // Append 12 trailing bytes
        val extraTrailer = encoded + byteArrayOf(0xA1.toByte(), 0x08.toByte()) + ByteArray(10)
        assertNull("Payload with extra trailing bytes must be rejected", HelloCodec.decode(extraTrailer))
    }
}


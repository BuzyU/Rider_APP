package com.ridervoice.mesh.audit

import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.protocol.AudioFormatId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HelloFormatsTlvTest {

    @Test
    fun helloFormats_roundTrip() {
        val payload = HelloPayload(
            name = "Rider-Alice",
            graphVersion = 42,
            neighborIds = listOf(UUID.randomUUID().toString()),
            onlineIdTag = "0123456789abcdef",
            supportedFormats = AudioFormatId.BITMASK_ALL
        )
        val encoded = HelloCodec.encode(payload)
        val decoded = HelloCodec.decode(encoded)

        assertNotNull("Decoded payload must not be null", decoded)
        assertEquals("Name must match", payload.name, decoded?.name)
        assertEquals("Graph version must match", payload.graphVersion, decoded?.graphVersion)
        assertEquals("Neighbors must match", payload.neighborIds, decoded?.neighborIds)
        assertEquals("Online tag must match", payload.onlineIdTag, decoded?.onlineIdTag)
        assertEquals("Supported formats must match", payload.supportedFormats, decoded?.supportedFormats)
    }

    @Test
    fun helloFormats_trailingGarbageRejected() {
        val payload = HelloPayload(
            name = "Rider-Bob",
            graphVersion = 1,
            neighborIds = emptyList(),
            onlineIdTag = null,
            supportedFormats = AudioFormatId.BITMASK_PCM
        )
        val encoded = HelloCodec.encode(payload)

        // Append 1 extra byte of trailing garbage
        val garbage1 = encoded + byteArrayOf(0x55)
        assertNull("Decoder must reject single trailing garbage byte", HelloCodec.decode(garbage1))

        // Append unknown TLV
        val unknownTlv = encoded + byteArrayOf(0x7F.toByte(), 0x02.toByte(), 0x00, 0x01)
        assertNull("Decoder must reject unknown TLV trailing bytes", HelloCodec.decode(unknownTlv))
    }

    @Test
    fun helloFormats_truncatedTlvRejected() {
        val payload = HelloPayload(
            name = "Rider-Charlie",
            graphVersion = 2,
            neighborIds = emptyList(),
            onlineIdTag = null,
            supportedFormats = AudioFormatId.BITMASK_ALL
        )
        val encoded = HelloCodec.encode(payload)

        // Truncate the last byte of the 4-byte formats TLV
        val truncated = encoded.copyOf(encoded.size - 1)
        assertNull("Decoder must reject truncated formats TLV", HelloCodec.decode(truncated))
    }
}

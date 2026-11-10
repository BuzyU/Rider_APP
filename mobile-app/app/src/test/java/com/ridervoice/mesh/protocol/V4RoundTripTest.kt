package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class V4RoundTripTest {

    private val payloadKey = ByteArray(32) { (it * 3 + 1).toByte() }
    private val selfId = "11111111-1111-4111-8111-111111111111"
    private val peerId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun opus16VoiceRoundTrip_exact69Bytes() {
        val opus40Bytes = ByteArray(40) { (it + 5).toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 42,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 100,
            payload = opus40Bytes
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("Opus 16k voice wire datagram must be exactly 69 bytes", 69, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue("Decode must succeed", decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertEquals(MeshPacketType.VOICE, p.type)
        assertEquals(AudioFormatId.OPUS_16K_MONO_20MS, p.audioFormat)
        assertEquals(42, p.sequence)
        assertEquals(100, p.epoch)
        assertEquals(5, p.ttl)
        assertEquals(0, p.hopCount)
        assertEquals(MeshPacketV4.shortId(selfId), p.sourceShortId)
        assertEquals(MeshPacketV4.shortId(selfId), p.previousHopShortId)
        assertArrayEquals(opus40Bytes, p.payload)
    }

    @Test
    fun opus12VoiceRoundTrip_exact59Bytes() {
        val opus30Bytes = ByteArray(30) { (it + 1).toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 77,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 101,
            payload = opus30Bytes
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("Opus 12k voice wire datagram must be exactly 59 bytes", 59, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertArrayEquals(opus30Bytes, p.payload)
    }

    @Test
    fun opus24VoiceRoundTrip_exact89Bytes() {
        val opus60Bytes = ByteArray(60) { (it * 2).toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 120,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 102,
            payload = opus60Bytes
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("Opus 24k voice wire datagram must be exactly 89 bytes", 89, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertArrayEquals(opus60Bytes, p.payload)
    }

    @Test
    fun dtxMarkerRoundTrip_exact30Bytes() {
        val marker = ByteArray(1) { 0 }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 200,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 103,
            payload = marker
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("DTX marker datagram must be exactly 30 bytes", 30, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertArrayEquals(marker, p.payload)
    }

    @Test
    fun pcmFallbackRoundTrip_exact669Bytes() {
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55.toByte() }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 300,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 104,
            payload = pcm
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("PCM fallback datagram must be exactly 669 bytes", 669, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertEquals(AudioFormatId.PCM16_16K_MONO, p.audioFormat)
        assertArrayEquals(pcm, p.payload)
    }

    @Test
    fun byePacketRoundTrip_exact29Bytes() {
        val packet = MeshPacket(
            type = MeshPacketType.BYE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 400,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.NONE,
            epoch = 105,
            payload = ByteArray(0)
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        assertEquals("BYE datagram must be exactly 29 bytes", 29, wire.size)

        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertEquals(MeshPacketType.BYE, p.type)
        assertEquals(0, p.payload.size)
    }

    @Test
    fun helloPacketRoundTrip() {
        val hello = HelloPayload(
            deviceId = selfId,
            name = "RiderAlice",
            graphVersion = 3,
            neighborIds = listOf(peerId),
            supportedFormats = AudioFormatId.BITMASK_ALL
        )
        val plainHello = HelloCodec.encode(hello)
        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 500,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.NONE,
            epoch = 106,
            payload = plainHello
        )

        val wire = MeshPacketV4.encode(packet, payloadKey)
        val decoded = MeshPacketV4.decode(wire, payloadKey)
        assertTrue(decoded is DecodeResult.Ok)
        val p = (decoded as DecodeResult.Ok).packet
        assertEquals(MeshPacketType.HELLO, p.type)

        val decodedHello = HelloCodec.decode(p.payload)
        assertEquals(hello.deviceId, decodedHello?.deviceId)
        assertEquals("RiderAlice", decodedHello?.name)
        assertEquals(3, decodedHello?.graphVersion)
        assertEquals(listOf(peerId), decodedHello?.neighborIds)
    }
}


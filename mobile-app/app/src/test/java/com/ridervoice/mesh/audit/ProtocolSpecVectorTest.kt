package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.MeshPacketV4
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionKdf
import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolSpecVectorTest {

    @Test
    fun protocolConstantsMatchSpec() {
        assertEquals("Mesh port must be 45454", 45454, MeshConfig.MESH_PORT)
        assertEquals("Magic must be 0x52564D31 (ASCII 'RVM1')", 0x52564D31, MeshPacketCodec.MAGIC)
        assertEquals("Protocol version must be 4", 4, MeshPacketCodec.VERSION)
        assertEquals("Header length must be 17 bytes", 17, MeshPacketCodec.HEADER_LEN)
        assertEquals("AEAD auth tag length must be 12 bytes", 12, MeshPacketCodec.TAG_LEN)
        assertEquals("GCM tag bytes must be 12 bytes", 12, MeshConfig.GCM_TAG_BYTES)
        assertEquals("PCM bytes per frame must be 640 (20ms at 16kHz mono)", 640, MeshConfig.BYTES_PER_FRAME)
        assertEquals("Encrypted voice payload must be 652 bytes", 652, MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES)

        val totalVoiceDatagramSize = MeshPacketCodec.HEADER_LEN + 40 + MeshPacketCodec.TAG_LEN
        assertEquals("Voice datagram size for Opus 16k must be exactly 69 bytes", 69, totalVoiceDatagramSize)
    }

    @Test
    fun packetTypeCodesMatchSpec() {
        assertEquals(1, MeshPacketType.HELLO.code)
        assertEquals(2, MeshPacketType.VOICE.code)
        assertEquals(3, MeshPacketType.BYE.code)
        assertEquals(0, MeshPacketType.VOICE.codeV4)
        assertEquals(1, MeshPacketType.HELLO.codeV4)
        assertEquals(2, MeshPacketType.BYE.codeV4)
        assertEquals(1, AudioFormatId.PCM16_16K_MONO)
        assertEquals(2, AudioFormatId.OPUS_16K_MONO_20MS)
    }

    @Test
    fun headerFieldOffsetsAndLengthsMatchSpec() {
        val dummyKey = ByteArray(32) { (it + 1).toByte() }
        val dummySource = "11111111-1111-4111-8111-111111111111"
        val dummyPrevHop = "22222222-2222-4222-8222-222222222222"

        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = dummySource,
            sequence = 12345,
            ttl = 5,
            hopCount = 1,
            previousHopId = dummyPrevHop,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 42,
            payload = ByteArray(40) { 0xAA.toByte() }
        )

        val encoded = MeshPacketCodec.encode(packet, dummyKey)
        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)

        // Offset 0 (1 B): vtf = ver(3)=4, type(2)=0(VOICE), fmt(3)=2(OPUS16) -> (4 shl 5) | (0 shl 3) | 2 = 0x82
        val vtf = buf.get().toInt() and 0xFF
        assertEquals("Version at offset 0 must be 4", 4, (vtf ushr 5) and 0x07)
        assertEquals("Type at offset 0 must be 0 (VOICE)", 0, (vtf ushr 3) and 0x03)
        assertEquals("Format at offset 0 must be 2 (OPUS16)", 2, vtf and 0x07)

        // Offset 1 (1 B): ttlHop = ttl(4)=5, hop(4)=1 -> (5 shl 4) | 1 = 0x51
        val ttlHop = buf.get().toInt() and 0xFF
        assertEquals("TTL at offset 1 must be 5", 5, (ttlHop ushr 4) and 0x0F)
        assertEquals("Hop at offset 1 must be 1", 1, ttlHop and 0x0F)

        // Offset 2 (4 B): srcShort
        assertEquals("srcShort at offset 2", MeshPacketV4.shortId(dummySource), buf.int)

        // Offset 6 (4 B): prevShort
        assertEquals("prevShort at offset 6", MeshPacketV4.shortId(dummyPrevHop), buf.int)

        // Offset 10 (4 B): epoch
        assertEquals("epoch at offset 10", 42, buf.int)

        // Offset 14 (3 B): seq
        val b14 = buf.get().toInt() and 0xFF
        val b15 = buf.get().toInt() and 0xFF
        val b16 = buf.get().toInt() and 0xFF
        val seq = (b14 shl 16) or (b15 shl 8) or b16
        assertEquals("sequence at offset 14 must be 12345", 12345, seq)

        assertEquals("Buffer position after header must be 17", 17, buf.position())
        assertEquals("Total datagram size for 40-byte payload + 12-byte tag must be 69 bytes", 69, encoded.size)
    }

    @Test
    fun fixedTestVectors_voiceHelloBye() {
        val payloadKey = ByteArray(32) { (it + 1).toByte() }
        val devA = "11111111-1111-4111-8111-111111111111"

        // 1. Voice Opus 16k
        val voicePkt = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = devA,
            previousHopId = devA,
            sequence = 1,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 1,
            payload = ByteArray(40) { 0x55 }
        )
        val voiceWire = MeshPacketCodec.encode(voicePkt, payloadKey)
        val voiceHex = PayloadCrypto.bytesToHex(voiceWire)
        assertEquals(69, voiceWire.size)
        assertEquals(
            "8250111111111111111100000001000001fd1eab64dcd0c2490875ea8fb3ffc59284c325110d750f0cb032114907e9d5ad0328ac0db646c6937501d53308b213b05cdd5298",
            voiceHex
        )

        // 2. Hello
        val helloPayload = HelloPayload(
            deviceId = devA,
            name = "Alice",
            graphVersion = 1,
            neighborIds = emptyList(),
            supportedFormats = 3
        )
        val helloPkt = MeshPacket(
            type = MeshPacketType.HELLO,
            sourceId = devA,
            previousHopId = devA,
            sequence = 1,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.NONE,
            epoch = 1,
            payload = HelloCodec.encode(helloPayload)
        )
        val helloWire = MeshPacketCodec.encode(helloPkt, payloadKey)
        val helloHex = PayloadCrypto.bytesToHex(helloWire)
        assertEquals(
            "8850111111111111111100000001000001e35d3186fa192364b2f517c95ac4a6c9d2056296e12a707d1e9dc2dc588febe4aba0dfa967f9eb734974323657",
            helloHex
        )

        // 3. Bye
        val byePkt = MeshPacket(
            type = MeshPacketType.BYE,
            sourceId = devA,
            previousHopId = devA,
            sequence = 1,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.NONE,
            epoch = 1,
            payload = ByteArray(0)
        )
        val byeWire = MeshPacketCodec.encode(byePkt, payloadKey)
        val byeHex = PayloadCrypto.bytesToHex(byeWire)
        assertEquals(29, byeWire.size)
        assertEquals(
            "90501111111111111111000000010000017f49d34882e571ae898a49d6",
            byeHex
        )

        // Assert all 3 round-trip decode cleanly
        assertTrue(MeshPacketCodec.decode(voiceWire, payloadKey) is DecodeResult.Ok)
        assertTrue(MeshPacketCodec.decode(helloWire, payloadKey) is DecodeResult.Ok)
        assertTrue(MeshPacketCodec.decode(byeWire, payloadKey) is DecodeResult.Ok)
    }

    @Test
    fun sessionKdfParametersMatchSpec() {
        assertEquals("PBKDF2 iterations must be 120,000", 120_000, SessionKdf.DEFAULT_ITERATIONS)
        assertEquals("Code length must be 16 characters", 16, OfflineSession.CODE_LENGTH)

        // Verify Crockford Base32 normalization and code validation
        assertNotNull(OfflineSession.fromCode("2345-6789-ABCD-EFGH", 1000L, 10))
        assertNotNull(OfflineSession.fromCode("23456789abcdefgh", 1000L, 10)) // lowercase
        assertNull(OfflineSession.fromCode("TOO-SHORT", 1000L, 10)) // invalid length
    }

    @Test
    fun antiFlappingConfigAndStateCountMatchSpec() {
        val config = AntiFlappingConfig()
        assertEquals("Failure confirmation must be 10,000ms", 10_000L, config.failureConfirmationMs)
        assertEquals("Recovery stability must be 8,000ms", 8_000L, config.recoveryStabilityMs)
        assertEquals("Min dwell must be 20,000ms", 20_000L, config.minDwellMs)
        assertEquals("Max transitions in window must be 3", 3, config.maxTransitionsPerWindow)
        assertEquals("Transition window must be 120,000ms", 120_000L, config.windowMs)

        // Exactly 8 states
        val states = AutoSwitchState.values()
        assertEquals("AutoSwitchState must have exactly 8 states", 8, states.size)
        assertTrue(states.contains(AutoSwitchState.ONLINE))
        assertTrue(states.contains(AutoSwitchState.ONLINE_SUSPECT))
        assertTrue(states.contains(AutoSwitchState.STARTING_OFFLINE_MESH))
        assertTrue(states.contains(AutoSwitchState.OFFLINE_ACTIVE))
        assertTrue(states.contains(AutoSwitchState.ONLINE_RECOVERING))
        assertTrue(states.contains(AutoSwitchState.HANDING_BACK_TO_LIVEKIT))
        assertTrue(states.contains(AutoSwitchState.FALLBACK_UNAVAILABLE))
        assertTrue(states.contains(AutoSwitchState.SESSION_ENDED))
    }
}

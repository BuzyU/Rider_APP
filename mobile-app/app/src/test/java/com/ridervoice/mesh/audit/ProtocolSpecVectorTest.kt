package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
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
        assertEquals("Protocol version must be 2", 2, MeshPacketCodec.VERSION)
        assertEquals("Header length must be 75 bytes", 75, MeshPacketCodec.HEADER_LEN)
        assertEquals("Outer auth tag length must be 16 bytes", 16, MeshPacketCodec.TAG_LEN)
        assertEquals("GCM tag bytes must be 16 bytes", 16, MeshConfig.GCM_TAG_BYTES)
        assertEquals("PCM bytes per frame must be 640 (20ms at 16kHz mono)", 640, MeshConfig.BYTES_PER_FRAME)
        assertEquals("Encrypted voice payload must be 656 bytes", 656, MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES)

        val totalVoiceDatagramSize = MeshPacketCodec.HEADER_LEN + MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES + MeshPacketCodec.TAG_LEN
        assertEquals("Voice datagram size must be exactly 747 bytes", 747, totalVoiceDatagramSize)
    }

    @Test
    fun packetTypeCodesMatchSpec() {
        assertEquals(1, MeshPacketType.HELLO.code)
        assertEquals(2, MeshPacketType.VOICE.code)
        assertEquals(3, MeshPacketType.BYE.code)
        assertEquals(1, AudioFormatId.PCM16_16K_MONO)
    }

    @Test
    fun headerFieldOffsetsAndLengthsMatchSpec() {
        val dummyKey = ByteArray(32) { it.toByte() }
        val dummySession = "11111111-1111-1111-1111-111111111111"
        val dummySource = "22222222-2222-2222-2222-222222222222"
        val dummyPrevHop = "33333333-3333-3333-3333-333333333333"

        val packet = com.ridervoice.mesh.protocol.MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = dummySession,
            sourceId = dummySource,
            sequence = 12345,
            captureTimestamp = 9876543210L,
            ttl = 5,
            hopCount = 1,
            previousHopId = dummyPrevHop,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 42,
            payload = ByteArray(10) { 0xAA.toByte() }
        )

        val encoded = MeshPacketCodec.encode(packet, dummyKey)
        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)

        assertEquals("Magic at offset 0", MeshPacketCodec.MAGIC, buf.int)
        assertEquals("Version at offset 4", 2, buf.get().toInt() and 0xFF)
        assertEquals("Type at offset 5", MeshPacketType.VOICE.code, buf.get().toInt() and 0xFF)

        val sessUuid = java.util.UUID(buf.long, buf.long).toString()
        assertEquals("Session UUID at offset 6", dummySession, sessUuid)

        val srcUuid = java.util.UUID(buf.long, buf.long).toString()
        assertEquals("Source UUID at offset 22", dummySource, srcUuid)

        assertEquals("Sequence at offset 38", 12345, buf.int)
        assertEquals("Timestamp at offset 42", 9876543210L, buf.long)
        assertEquals("TTL at offset 50", 5, buf.get().toInt() and 0xFF)
        assertEquals("Hop at offset 51", 1, buf.get().toInt() and 0xFF)

        val prevUuid = java.util.UUID(buf.long, buf.long).toString()
        assertEquals("PreviousHop UUID at offset 52", dummyPrevHop, prevUuid)

        assertEquals("Format at offset 68", AudioFormatId.PCM16_16K_MONO, buf.get().toInt() and 0xFF)
        assertEquals("Epoch at offset 69", 42, buf.int)
        assertEquals("Payload length at offset 73", 10, buf.short.toInt() and 0xFFFF)
        assertEquals("Buffer position after header must be 75", 75, buf.position())
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

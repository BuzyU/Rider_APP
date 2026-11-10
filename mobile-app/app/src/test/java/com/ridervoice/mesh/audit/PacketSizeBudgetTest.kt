package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.MeshPacketV4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketSizeBudgetTest {

    private val dummyKey = ByteArray(32) { 0x01 }
    private val dummyId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun v4ConstantsAndVoiceBudget_meetTargets() {
        assertEquals("Protocol v4 header length must be 17 bytes", 17, MeshPacketCodec.HEADER_LEN)
        assertEquals("Single AEAD GCM tag must be 12 bytes", 12, MeshPacketCodec.TAG_LEN)
        assertEquals("PCM frame size must be 640 bytes", 640, MeshConfig.BYTES_PER_FRAME)
        assertEquals("Max voice datagram budget must be 100 bytes", 100, MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        // 1. Opus 16 kbit/s: 40 bytes audio -> 17 + 40 + 12 = 69 B
        val wireOpus16 = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = dummyId, previousHopId = dummyId, sequence = 1, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, epoch = 1, payload = ByteArray(40)),
            dummyKey
        )
        assertEquals("voice(Opus16) must be exactly 69 bytes", 69, wireOpus16.size)
        assertTrue(wireOpus16.size <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        // 2. Opus 12 kbit/s: 30 bytes audio -> 17 + 30 + 12 = 59 B
        val wireOpus12 = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = dummyId, previousHopId = dummyId, sequence = 2, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, epoch = 1, payload = ByteArray(30)),
            dummyKey
        )
        assertEquals("voice(Opus12) must be exactly 59 bytes", 59, wireOpus12.size)
        assertTrue(wireOpus12.size <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        // 3. Opus 24 kbit/s: 60 bytes audio -> 17 + 60 + 12 = 89 B
        val wireOpus24 = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = dummyId, previousHopId = dummyId, sequence = 3, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, epoch = 1, payload = ByteArray(60)),
            dummyKey
        )
        assertEquals("voice(Opus24) must be exactly 89 bytes", 89, wireOpus24.size)
        assertTrue(wireOpus24.size <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        // 4. DTX / keep-alive marker (1 byte): 17 + 1 + 12 = 30 B
        val wireMarker = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = dummyId, previousHopId = dummyId, sequence = 4, audioFormat = AudioFormatId.OPUS_16K_MONO_20MS, epoch = 1, payload = ByteArray(1)),
            dummyKey
        )
        assertEquals("marker must be exactly 30 bytes", 30, wireMarker.size)
        assertTrue(wireMarker.size <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        // 5. PCM fallback (640 bytes): 17 + 640 + 12 = 669 B
        val wirePcm = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.VOICE, sourceId = dummyId, previousHopId = dummyId, sequence = 5, audioFormat = AudioFormatId.PCM16_16K_MONO, epoch = 1, payload = ByteArray(640)),
            dummyKey
        )
        assertEquals("voice(PCM) must be exactly 669 bytes", 669, wirePcm.size)

        // 6. BYE packet (empty plaintext): 17 + 0 + 12 = 29 B
        val wireBye = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.BYE, sourceId = dummyId, previousHopId = dummyId, sequence = 6, audioFormat = AudioFormatId.NONE, epoch = 1, payload = ByteArray(0)),
            dummyKey
        )
        assertEquals("BYE must be exactly 29 bytes", 29, wireBye.size)
    }
}

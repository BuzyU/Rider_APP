package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketSizeBudgetTest {

    @Test
    fun voicePacketSizeBudget_matchesConstants() {
        assertEquals("Protocol v3 header length must be 75 bytes", 75, MeshPacketCodec.HEADER_LEN)
        assertEquals("Outer HMAC authentication tag must be 16 bytes", 16, MeshPacketCodec.TAG_LEN)
        assertEquals("PCM frame size must be 640 bytes", 640, MeshConfig.BYTES_PER_FRAME)
        assertEquals("Encrypted voice payload must be 656 bytes", 656, MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES)

        // Opus frame budget
        val opusFrameBytes = 40
        val encryptedOpusVoicePayloadBytes = opusFrameBytes + MeshConfig.GCM_TAG_BYTES
        val totalOpusVoiceDatagramBytes = MeshPacketCodec.HEADER_LEN + encryptedOpusVoicePayloadBytes + MeshPacketCodec.TAG_LEN
        assertEquals("Total Opus voice datagram on v3 must be exactly 147 bytes", 147, totalOpusVoiceDatagramBytes)
        assertEquals("Max voice datagram budget must be 200 bytes", 200, MeshConfig.MAX_VOICE_DATAGRAM_BYTES)
        assertTrue("Opus datagram must fit within max voice datagram budget", totalOpusVoiceDatagramBytes <= MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        println("Opus voice packet size budget on v3: $totalOpusVoiceDatagramBytes bytes (max budget ${MeshConfig.MAX_VOICE_DATAGRAM_BYTES})")
    }
}

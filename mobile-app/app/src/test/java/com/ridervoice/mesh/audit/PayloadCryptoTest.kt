package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T31 regression test suite for E16: Authenticated Payload Encryption (AES-GCM) & Protocol v2.
 */
class PayloadCryptoTest {

    private val sampleKey = ByteArray(32) { (it + 1).toByte() }
    private val sessionId = UUID.randomUUID().toString()
    private val sourceId = UUID.randomUUID().toString()

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val plain = "Secret voice communications payload that must not be readable in cleartext!".toByteArray()
        val epoch = 0x12345678
        val seq = 42
        val type = MeshPacketType.VOICE.code

        val nonce = PayloadCrypto.nonce(epoch, seq, type)
        val aad = PayloadCrypto.aad(sessionId, sourceId, type, epoch, seq, 1000L, AudioFormatId.PCM16_16K_MONO)
        val sealed = PayloadCrypto.seal(sampleKey, nonce, aad, plain)

        // Ensure sealed is plain.size + 16 (GCM tag)
        assertEquals(plain.size + PayloadCrypto.TAG_LEN, sealed.size)

        // Ensure plaintext does not appear in ciphertext
        val sealedStr = String(sealed, Charsets.ISO_8859_1)
        val plainStr = String(plain, Charsets.ISO_8859_1)
        assertFalse("Ciphertext must not contain plaintext", sealedStr.contains(plainStr))

        val decrypted = PayloadCrypto.open(sampleKey, nonce, aad, sealed)
        assertNotNull(decrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun wrongKeyFailsDecryption() {
        val plain = ByteArray(640) { 0x55.toByte() }
        val epoch = 1
        val seq = 100
        val type = MeshPacketType.VOICE.code
        val nonce = PayloadCrypto.nonce(epoch, seq, type)
        val aad = PayloadCrypto.aad(sessionId, sourceId, type, epoch, seq, 2000L, AudioFormatId.PCM16_16K_MONO)

        val sealed = PayloadCrypto.seal(sampleKey, nonce, aad, plain)

        val wrongKey = sampleKey.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val decrypted = PayloadCrypto.open(wrongKey, nonce, aad, sealed)
        assertNull("Decryption with wrong key must return null", decrypted)
    }

    @Test
    fun aadTamperFailsDecryption() {
        val plain = ByteArray(640) { 0x33.toByte() }
        val epoch = 5
        val seq = 200
        val type = MeshPacketType.VOICE.code
        val nonce = PayloadCrypto.nonce(epoch, seq, type)
        val aad = PayloadCrypto.aad(sessionId, sourceId, type, epoch, seq, 3000L, AudioFormatId.PCM16_16K_MONO)

        val sealed = PayloadCrypto.seal(sampleKey, nonce, aad, plain)

        // Tamper each byte of AAD and ensure open fails
        for (i in aad.indices) {
            val tamperedAad = aad.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val decrypted = PayloadCrypto.open(sampleKey, nonce, tamperedAad, sealed)
            assertNull("Tampered AAD byte $i must reject decryption", decrypted)
        }
    }

    @Test
    fun differentSequenceProducesDifferentCiphertext() {
        val plain = ByteArray(640) { 0x77.toByte() }
        val epoch = 10
        val type = MeshPacketType.VOICE.code

        val nonce1 = PayloadCrypto.nonce(epoch, 1, type)
        val aad1 = PayloadCrypto.aad(sessionId, sourceId, type, epoch, 1, 1000L, AudioFormatId.PCM16_16K_MONO)
        val sealed1 = PayloadCrypto.seal(sampleKey, nonce1, aad1, plain)

        val nonce2 = PayloadCrypto.nonce(epoch, 2, type)
        val aad2 = PayloadCrypto.aad(sessionId, sourceId, type, epoch, 2, 1020L, AudioFormatId.PCM16_16K_MONO)
        val sealed2 = PayloadCrypto.seal(sampleKey, nonce2, aad2, plain)

        assertFalse("Different sequences must yield different ciphertexts", sealed1.contentEquals(sealed2))
    }

    @Test
    fun nonceUniquenessOverEpochsTypeAndSequences() {
        // Test combinations: 3 epochs x 2 types x 10,000 sequences = 60,000 nonces (tested in memory)
        val epochs = listOf(0, 1, 0x7FFFFFFF)
        val types = listOf(MeshPacketType.VOICE.code, MeshPacketType.HELLO.code)
        val countPerCombo = 10_000
        val seen = HashSet<ByteBuffer>(epochs.size * types.size * countPerCombo)

        for (ep in epochs) {
            for (t in types) {
                for (seq in 1..countPerCombo) {
                    val n = ByteBuffer.wrap(PayloadCrypto.nonce(ep, seq, t))
                    assertTrue("Nonce must be unique: collision at epoch=$ep type=$t seq=$seq", seen.add(n))
                }
            }
        }
        assertEquals(epochs.size * types.size * countPerCombo, seen.size)
    }

    @Test
    fun knownAnswerVectorNistSp80038D() {
        // NIST SP 800-38D Test Case 14 (AES-256 GCM)
        // Key: 32 bytes of 0x00
        // IV: 12 bytes of 0x00
        // PT: 16 bytes of 0x00
        // AAD: 0 bytes
        // Expected Ciphertext (16 bytes): cea7403d4d606b6e074ec5d3baf39d18
        // Expected Tag (16 bytes): d0d1c8a799996bf0265b98b5d48ab919
        val key = ByteArray(32)
        val iv = ByteArray(12)
        val pt = ByteArray(16)
        val aad = ByteArray(0)

        val sealed = PayloadCrypto.seal(key, iv, aad, pt)
        val expectedCt = "cea7403d4d606b6e074ec5d3baf39d18"
        val expectedTag = "d0d1c8a799996bf0265b98b5d48ab919"
        val expectedSealedHex = expectedCt + expectedTag

        val actualSealedHex = sealed.joinToString("") { "%02x".format(it) }
        assertEquals(expectedSealedHex, actualSealedHex)

        val opened = PayloadCrypto.open(key, iv, aad, sealed)
        assertNotNull(opened)
        assertArrayEquals(pt, opened)
    }

    @Test
    fun relayLeavesInnerCiphertextIdentical() {
        val originalPacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sessionId,
            sourceId = sourceId,
            sequence = 1,
            captureTimestamp = 1000L,
            ttl = 5,
            hopCount = 0,
            previousHopId = sourceId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1234,
            payload = ByteArray(MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES) { 0xAA.toByte() }
        )

        val relayId = UUID.randomUUID().toString()
        val forwarded = originalPacket.forwardedBy(relayId)

        assertEquals(4, forwarded.ttl)
        assertEquals(1, forwarded.hopCount)
        assertEquals(relayId, forwarded.previousHopId)
        assertEquals(originalPacket.sourceId, forwarded.sourceId)
        assertEquals(originalPacket.sequence, forwarded.sequence)
        assertEquals(originalPacket.epoch, forwarded.epoch)
        // Crucial invariant: inner payload ciphertext is byte-for-byte identical
        assertArrayEquals(originalPacket.payload, forwarded.payload)
    }
}


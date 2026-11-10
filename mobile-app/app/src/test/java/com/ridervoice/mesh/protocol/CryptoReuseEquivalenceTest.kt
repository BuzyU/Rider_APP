package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoReuseEquivalenceTest {

    @Test
    fun threadLocalMac_matchesFreshMacInstancesAcrossMultipleCalls() {
        val key = ByteArray(32) { (it * 7).toByte() }
        for (i in 0 until 100) {
            val payload = ByteArray(MeshConfig.BYTES_PER_FRAME) { ((i + it) and 0xFF).toByte() }

            // Fresh Mac instance
            val freshMac = Mac.getInstance("HmacSHA256")
            freshMac.init(SecretKeySpec(key, "HmacSHA256"))
            val freshTag = freshMac.doFinal(payload).copyOf(16)

            // ThreadLocal-backed tag computation in MeshPacketCodec
            val reusedTag = MeshPacketCodec.tag(payload, key)

            assertArrayEquals("ThreadLocal Mac tag must match fresh Mac instance on iteration $i", freshTag, reusedTag)
        }
    }

    @Test
    fun threadLocalCipher_matchesFreshCipherInstancesAcrossMultipleCalls() {
        val key = ByteArray(32) { (it * 13).toByte() }
        val keySpec = SecretKeySpec(key, "AES")

        for (i in 0 until 100) {
            val plain = ByteArray(MeshConfig.BYTES_PER_FRAME) { ((i * 3 + it) and 0xFF).toByte() }
            val nonce = PayloadCrypto.nonce(epoch = 1, sequence = i, typeCode = MeshPacketType.VOICE.code)
            val aad = PayloadCrypto.aad(
                sessionHi = 0x12345678L,
                sessionLo = 0x9abcdef0L,
                sourceHi = 0x11223344L,
                sourceLo = 0x55667788L,
                typeCode = MeshPacketType.VOICE.code,
                epoch = 1,
                sequence = i,
                captureTimestamp = 10_000L + i * 20L,
                audioFormat = AudioFormatId.PCM16_16K_MONO
            )

            // Fresh Cipher instance
            val freshCipher = Cipher.getInstance("AES/GCM/NoPadding")
            freshCipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            freshCipher.updateAAD(aad)
            val freshSealed = freshCipher.doFinal(plain)

            // ThreadLocal seal
            val reusedSealed = PayloadCrypto.seal(keySpec, nonce, aad, plain)
            assertArrayEquals("ThreadLocal Cipher seal must match fresh Cipher on iteration $i", freshSealed, reusedSealed)

            // Fresh decrypt
            val freshDecrypt = Cipher.getInstance("AES/GCM/NoPadding")
            freshDecrypt.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            freshDecrypt.updateAAD(aad)
            val freshPlain = freshDecrypt.doFinal(reusedSealed)
            assertArrayEquals("Fresh Cipher open must recover plaintext on iteration $i", plain, freshPlain)

            // ThreadLocal decrypt
            val reusedPlain = PayloadCrypto.open(keySpec, nonce, aad, freshSealed)
            assertArrayEquals("ThreadLocal Cipher open must recover plaintext on iteration $i", plain, reusedPlain)
        }
    }
}


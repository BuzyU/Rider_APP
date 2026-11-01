package com.ridervoice.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Inner AEAD payload encryption (AES-256-GCM) protecting voice and control payloads end-to-end.
 *
 * Senders seal their payloads once before transmission. Relays never decrypt or re-encrypt;
 * they only update mutable hop fields in the outer header and re-compute the hop HMAC.
 */
object PayloadCrypto {
    const val TAG_LEN = 16
    const val NONCE_LEN = 12

    fun nonce(epoch: Int, sequence: Int, typeCode: Int): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).order(ByteOrder.BIG_ENDIAN)
            .putInt(epoch)
            .putInt(sequence)
            .put(typeCode.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .array()

    fun aad(
        sessionId: String,
        sourceId: String,
        typeCode: Int,
        epoch: Int,
        sequence: Int,
        captureTimestamp: Long,
        audioFormat: Int
    ): ByteArray = ByteBuffer.allocate(16 + 16 + 1 + 4 + 4 + 8 + 1).order(ByteOrder.BIG_ENDIAN)
        .put(MeshPacketCodec.uuidBytes(sessionId))
        .put(MeshPacketCodec.uuidBytes(sourceId))
        .put(typeCode.toByte())
        .putInt(epoch)
        .putInt(sequence)
        .putLong(captureTimestamp)
        .put(audioFormat.toByte())
        .array()

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        return c.doFinal(plain)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        c.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }
}


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

    private fun hmac(salt: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(data)

    /**
     * Derives a dedicated 32-byte AES payload subkey for a specific audio/control source using HKDF (RFC 5869).
     * Prevents (key, IV) reuse across independent or uncoordinated source sequence spaces.
     */
    fun sourceKey(payloadKey: ByteArray, sourceId: String): ByteArray {
        val prk = hmac(salt = "rvmesh/v3/src".toByteArray(Charsets.UTF_8), data = payloadKey) // HKDF-Extract
        val srcBytes = runCatching { MeshPacketCodec.uuidBytes(sourceId) }.getOrElse { sourceId.toByteArray(Charsets.UTF_8) }
        return hmac(salt = prk, data = srcBytes + byteArrayOf(1)).copyOf(32) // HKDF-Expand, one block
    }

    /**
     * Computes an 8-byte authentication tag identifying an online LiveKit identity.
     * tag = HMAC-SHA256(payloadKey, livekitIdentityUtf8)[0..8]
     */
    fun computeOnlineIdTag(payloadKey: ByteArray, livekitIdentity: String): ByteArray =
        hmac(salt = payloadKey, data = livekitIdentity.toByteArray(Charsets.UTF_8)).copyOf(8)

    fun nonce(epoch: Int, sequence: Int, typeCode: Int): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).order(ByteOrder.BIG_ENDIAN)
            .putInt(epoch)
            .putInt(sequence)
            .put(typeCode.toByte())
            .put(ByteArray(3))
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

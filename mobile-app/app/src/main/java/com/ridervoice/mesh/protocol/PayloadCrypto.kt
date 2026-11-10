package com.ridervoice.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Inner AEAD payload encryption (AES-256-GCM) protecting voice and control payloads end-to-end.
 *
 * Senders seal their payloads once before transmission. Every session member decrypts;
 * relays only rewrite hop fields and re-authenticate.
 */
object PayloadCrypto {
    const val TAG_LEN = 16
    const val NONCE_LEN = 12

    const val TAG_LEN_V4 = 12
    const val NONCE_LEN_V4 = 12
    const val AAD_LEN_V4 = 12

    private val cipherThreadLocal: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    private val hmacThreadLocal: ThreadLocal<javax.crypto.Mac> = ThreadLocal.withInitial { javax.crypto.Mac.getInstance("HmacSHA256") }

    private data class SourceKeyCacheKey(val payloadKeyHash: Int, val srcShort: Int, val epoch: Int)
    private val v4KeyCache = object : LinkedHashMap<SourceKeyCacheKey, SecretKeySpec>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceKeyCacheKey, SecretKeySpec>?): Boolean {
            return size > 16
        }
    }

    private fun hmac(salt: ByteArray, data: ByteArray): ByteArray {
        val mac = hmacThreadLocal.get()!!
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Derives a dedicated 32-byte AES payload subkey for a specific audio/control source using HKDF (RFC 5869).
     * Prevents (key, IV) reuse across independent or uncoordinated source sequence spaces.
     */
    fun sourceKey(payloadKey: ByteArray, sourceId: String): ByteArray {
        val prk = hmac(salt = "rvmesh/v3/src".toByteArray(Charsets.UTF_8), data = payloadKey) // HKDF-Extract
        val srcBytes = MeshPacketCodec.uuidBytes(sourceId)
        return hmac(salt = prk, data = srcBytes + byteArrayOf(1)).copyOf(32) // HKDF-Expand, one block
    }

    /**
     * Protocol v4: Derives a dedicated 32-byte AES payload subkey using HKDF-SHA256:
     * sourceKey = HKDF-SHA256(ikm = payloadKey, salt = "rvmesh/v4/src", info = srcShort(4) || epoch(4))
     */
    fun sourceKeyV4(payloadKey: ByteArray, srcShort: Int, epoch: Int): ByteArray {
        val prk = hmac(salt = "rvmesh/v4/src".toByteArray(Charsets.UTF_8), data = payloadKey) // HKDF-Extract
        val info = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(srcShort)
            .putInt(epoch)
            .array()
        return hmac(salt = prk, data = info + byteArrayOf(1)).copyOf(32) // HKDF-Expand, one block
    }

    fun sourceKeySpecV4(payloadKey: ByteArray, srcShort: Int, epoch: Int): SecretKeySpec {
        val key = SourceKeyCacheKey(payloadKey.contentHashCode(), srcShort, epoch)
        synchronized(v4KeyCache) {
            val cached = v4KeyCache[key]
            if (cached != null) return cached
            val raw = sourceKeyV4(payloadKey, srcShort, epoch)
            val spec = SecretKeySpec(raw, "AES")
            v4KeyCache[key] = spec
            return spec
        }
    }

    fun clearKeyCache() {
        synchronized(v4KeyCache) {
            v4KeyCache.clear()
        }
    }

    fun nonceV4(typeCode: Int, seq: Int, epoch: Int): ByteArray =
        ByteBuffer.allocate(NONCE_LEN_V4).order(ByteOrder.BIG_ENDIAN)
            .put(typeCode.toByte())
            .put((seq ushr 16).toByte())
            .put((seq ushr 8).toByte())
            .put(seq.toByte())
            .putInt(0)
            .putInt(epoch)
            .array()

    fun aadV4(vtf: Byte, srcShort: Int, epoch: Int, seq: Int): ByteArray =
        ByteBuffer.allocate(AAD_LEN_V4).order(ByteOrder.BIG_ENDIAN)
            .put(vtf)
            .putInt(srcShort)
            .putInt(epoch)
            .put((seq ushr 16).toByte())
            .put((seq ushr 8).toByte())
            .put(seq.toByte())
            .array()

    fun sealV4(keySpec: SecretKeySpec, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = try {
            val cached = cipherThreadLocal.get()!!
            cached.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(96, nonce)
            )
            cached
        } catch (e: InvalidAlgorithmParameterException) {
            val fresh = Cipher.getInstance("AES/GCM/NoPadding")
            cipherThreadLocal.set(fresh)
            fresh.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(96, nonce)
            )
            fresh
        }
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        return c.doFinal(plain)
    }

    fun sealV4(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        sealV4(SecretKeySpec(key, "AES"), nonce, aad, plain)

    fun openV4(keySpec: SecretKeySpec, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = cipherThreadLocal.get()!!
        c.init(
            Cipher.DECRYPT_MODE,
            keySpec,
            GCMParameterSpec(96, nonce)
        )
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        c.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }

    fun openV4(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        openV4(SecretKeySpec(key, "AES"), nonce, aad, sealed)

    /**
     * Converts a ByteArray to a lowercase hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    /**
     * Converts a 16-character hex string into an 8-byte ByteArray.
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 16) { "onlineIdTag must be 16 hex characters (8 bytes)" }
        val bytes = ByteArray(8)
        for (i in 0 until 8) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high != -1 && low != -1) { "Invalid hex in onlineIdTag: $hex" }
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /**
     * Computes a 16-character lowercase hex tag identifying an online LiveKit identity.
     * Derives onlineKey = hkdfExpand(extract(salt="rvmesh/v3/online-id", payloadKey), info="tag")
     * and returns HMAC(onlineKey, livekitIdentityUtf8)[0..8] as hex.
     */
    fun computeOnlineIdTag(payloadKey: ByteArray, livekitIdentity: String): String {
        val prk = hmac(salt = "rvmesh/v3/online-id".toByteArray(Charsets.UTF_8), data = payloadKey)
        val onlineKey = hmac(salt = prk, data = "tag".toByteArray(Charsets.UTF_8) + byteArrayOf(1)).copyOf(32)
        val tagBytes = hmac(salt = onlineKey, data = livekitIdentity.toByteArray(Charsets.UTF_8)).copyOf(8)
        return bytesToHex(tagBytes)
    }

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
    ): ByteArray = aad(
        sessionHi = MeshPacketCodec.uuidParts(sessionId).first,
        sessionLo = MeshPacketCodec.uuidParts(sessionId).second,
        sourceHi = MeshPacketCodec.uuidParts(sourceId).first,
        sourceLo = MeshPacketCodec.uuidParts(sourceId).second,
        typeCode = typeCode,
        epoch = epoch,
        sequence = sequence,
        captureTimestamp = captureTimestamp,
        audioFormat = audioFormat
    )

    fun aad(
        sessionHi: Long,
        sessionLo: Long,
        sourceHi: Long,
        sourceLo: Long,
        typeCode: Int,
        epoch: Int,
        sequence: Int,
        captureTimestamp: Long,
        audioFormat: Int
    ): ByteArray = ByteBuffer.allocate(16 + 16 + 1 + 4 + 4 + 8 + 1).order(ByteOrder.BIG_ENDIAN)
        .putLong(sessionHi)
        .putLong(sessionLo)
        .putLong(sourceHi)
        .putLong(sourceLo)
        .put(typeCode.toByte())
        .putInt(epoch)
        .putInt(sequence)
        .putLong(captureTimestamp)
        .put(audioFormat.toByte())
        .array()

    fun seal(keySpec: SecretKeySpec, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = try {
            val cached = cipherThreadLocal.get()!!
            cached.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(128, nonce)
            )
            cached
        } catch (e: InvalidAlgorithmParameterException) {
            val fresh = Cipher.getInstance("AES/GCM/NoPadding")
            cipherThreadLocal.set(fresh)
            fresh.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(128, nonce)
            )
            fresh
        }
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        return c.doFinal(plain)
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        seal(SecretKeySpec(key, "AES"), nonce, aad, plain)

    fun open(keySpec: SecretKeySpec, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = cipherThreadLocal.get()!!
        c.init(
            Cipher.DECRYPT_MODE,
            keySpec,
            GCMParameterSpec(128, nonce)
        )
        if (aad.isNotEmpty()) {
            c.updateAAD(aad)
        }
        c.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        open(SecretKeySpec(key, "AES"), nonce, aad, sealed)
}

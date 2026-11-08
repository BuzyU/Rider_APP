package com.ridervoice.mesh.session

import com.ridervoice.mesh.MeshConfig
import java.security.SecureRandom

/**
 * Standalone offline session. No backend, Firebase, Internet or LiveKit involvement.
 *
 * The sessionId, authentication key, and payload encryption key are all derived from the pairing
 * code via PBKDF2-HMAC-SHA256 (SessionKdf). The code is 16 characters from Crockford Base32 (80 bits)
 * formatted in groups of 4 (e.g. K7QX-M2PA-9TDE-4WZH).
 */
class OfflineSession private constructor(
    val sessionId: String,
    private val code: CharArray,
    private val key: ByteArray,
    private val pKey: ByteArray,
    val createdAt: Long,
    val expiresAt: Long,
    val maxDevices: Int = MeshConfig.MAX_DEVICES
) : AutoCloseable {
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed

    fun isExpired(now: Long): Boolean = now >= expiresAt


    /** DISPLAY ONLY (lobby). Empty after close(). */
    val pairingCode: String get() = if (closed) "" else String(code)

    /** 32-byte HMAC key derived from the pairing code. Never sent over the wire. */
    fun authKey(): ByteArray {
        check(!closed) { "session closed" }
        return key.copyOf()
    }

    /** 32-byte AES-256 payload encryption key derived from the pairing code. Never sent over the wire. */
    fun payloadKey(): ByteArray {
        check(!closed) { "session closed" }
        return pKey.copyOf()
    }

    override fun close() {
        closed = true
        key.fill(0)
        pKey.fill(0)
        code.fill('\u0000')
    }

    override fun toString(): String =
        "OfflineSession(id=$sessionId, expiresAt=$expiresAt)"

    override fun equals(other: Any?): Boolean =
        other is OfflineSession &&
            sessionId == other.sessionId &&
            pairingCode == other.pairingCode &&
            key.contentEquals(other.key) &&
            pKey.contentEquals(other.pKey) &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            maxDevices == other.maxDevices

    override fun hashCode(): Int = sessionId.hashCode()

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  // 32 symbols Crockford Base32
        const val CODE_LENGTH = 16

        fun create(
            now: Long,
            random: SecureRandom = SecureRandom(),
            iterations: Int = SessionKdf.DEFAULT_ITERATIONS
        ): OfflineSession {
            val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return fromCode(code, now, iterations) ?: error("Failed to create session from generated code")
        }

        /** Returns null if the code is malformed. Local expiry is relative to when this device joined. */
        fun fromCode(
            rawCode: String,
            now: Long,
            iterations: Int = SessionKdf.DEFAULT_ITERATIONS,
            expiresAtOverride: Long? = null
        ): OfflineSession? {
            val code = normalize(rawCode) ?: return null
            val derived = SessionKdf.derive(code, iterations)
            return OfflineSession(
                sessionId = derived.sessionId,
                code = code.toCharArray(),
                key = derived.authKey,
                pKey = derived.payloadKey,
                createdAt = now,
                expiresAt = expiresAtOverride ?: (now + MeshConfig.SESSION_LIFETIME_MS)
            )
        }

        fun normalize(raw: String): String? {
            val code = raw.trim().uppercase()
                .replace("-", "")
                .replace(" ", "")
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')
            if (code.length != CODE_LENGTH) return null
            return if (code.all { it in ALPHABET }) code else null
        }

        fun format(code: String): String = code.chunked(4).joinToString("-")
    }
}

/** Tracks session membership and enforces the device cap (the 7th distinct device is rejected). */
class SessionManager(
    val session: OfflineSession,
    private val selfId: String,
    private val clock: () -> Long
) {
    private val lastSeen = LinkedHashMap<String, Long>().apply { put(selfId, clock()) }

    enum class Admission { ADMITTED, ALREADY_MEMBER, REJECTED_FULL, REJECTED_EXPIRED }

    @Synchronized
    fun admit(deviceId: String): Admission {
        val now = clock()
        if (session.isExpired(now)) return Admission.REJECTED_EXPIRED
        purgeStale(now)
        if (lastSeen.containsKey(deviceId)) {
            lastSeen[deviceId] = now
            return Admission.ALREADY_MEMBER
        }
        if (lastSeen.size >= session.maxDevices) return Admission.REJECTED_FULL
        lastSeen[deviceId] = now
        return Admission.ADMITTED
    }


    /**
     * Non-mutating joint admission check for pre-AEAD validation of relayed packets
     * where both [sourceId] and [previousHopId] may be new members.
     */
    @Synchronized
    fun canAdmitAll(deviceIds: Collection<String>): Admission {
        val now = clock()
        if (session.isExpired(now)) return Admission.REJECTED_EXPIRED
        purgeStale(now)
        val distinctIds = deviceIds.distinct()
        val newCount = distinctIds.count { !lastSeen.containsKey(it) }
        if (newCount == 0) return Admission.ALREADY_MEMBER
        if (lastSeen.size + newCount > session.maxDevices) return Admission.REJECTED_FULL
        return Admission.ADMITTED
    }

    @Synchronized
    fun touch(deviceId: String) { if (lastSeen.containsKey(deviceId)) lastSeen[deviceId] = clock() }

    @Synchronized
    fun remove(deviceId: String) { if (deviceId != selfId) lastSeen.remove(deviceId) }

    @Synchronized
    fun members(): Set<String> { purgeStale(clock()); return lastSeen.keys.toSet() }


    /** Leaving clears all remote membership state. */
    @Synchronized
    fun clear() { lastSeen.clear(); lastSeen[selfId] = clock() }

    private fun purgeStale(now: Long) {
        val it = lastSeen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != selfId && now - e.value > MeshConfig.MEMBER_TIMEOUT_MS) it.remove()
        }
    }
}

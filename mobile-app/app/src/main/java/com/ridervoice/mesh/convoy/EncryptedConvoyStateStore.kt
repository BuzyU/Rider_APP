package com.ridervoice.mesh.convoy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ridervoice.mesh.MeshConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32

/**
 * Synchronous key-value storage backend abstraction enabling deterministic JVM tests
 * alongside Android [EncryptedSharedPreferences].
 */
interface ConvoyKeyValueBackend {
    fun getString(key: String): String?
    fun putStringSync(key: String, value: String): Boolean
    fun removeSync(key: String): Boolean
    fun clearSync(): Boolean
}

class EncryptedPrefsConvoyBackend(context: Context) : ConvoyKeyValueBackend {
    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context, CONVOY_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    override fun getString(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (_: Exception) {
        null
    }

    override fun putStringSync(key: String, value: String): Boolean = try {
        prefs?.edit()?.putString(key, value)?.commit() ?: false
    } catch (_: Exception) {
        false
    }

    override fun removeSync(key: String): Boolean = try {
        prefs?.edit()?.remove(key)?.commit() ?: false
    } catch (_: Exception) {
        false
    }

    override fun clearSync(): Boolean = try {
        prefs?.edit()?.clear()?.commit() ?: false
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PREFS_NAME = "rvmesh_convoy_state"
        const val CONVOY_MASTER_KEY_ALIAS = "rvmesh_backup_master_key"
    }
}

class InMemoryConvoyKeyValueBackend : ConvoyKeyValueBackend {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putStringSync(key: String, value: String): Boolean {
        map[key] = value
        return true
    }

    override fun removeSync(key: String): Boolean {
        map.remove(key)
        return true
    }

    override fun clearSync(): Boolean {
        map.clear()
        return true
    }
}

/**
 * Atomic, write-ahead encrypted persistence for convoy transport switching state (v7 Section 5.3.7 & 16).
 *
 * Uses synchronous `.commit()` on [EncryptedSharedPreferences] (or injected backend in tests)
 * and appends a CRC32 integrity trailer over every serialized payload so torn or tampered entries
 * fail closed with `generationUnknown = true`.
 */
class EncryptedConvoyStateStore(
    private val backend: ConvoyKeyValueBackend,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : ConvoyStatePersistence {

    constructor(context: Context, nowMs: () -> Long = { System.currentTimeMillis() }) : this(
        EncryptedPrefsConvoyBackend(context),
        nowMs
    )

    private val lock = Any()

    override fun load(sessionId: UUID): PersistedConvoyState? = synchronized(lock) {
        val rawState = backend.getString(keyForState(sessionId))
        val rawMem = backend.getString(keyForMembership(sessionId))
        val membership = rawMem?.let { decodeMembership(sessionId, it) }

        if (rawState == null) {
            if (membership != null) {
                return PersistedConvoyState(
                    sessionId = sessionId,
                    membership = membership,
                    acceptedDecision = null,
                    generationHighWater = 0L,
                    generationUnknown = false,
                    transitionTimestampsMs = emptyList(),
                    expiresAtMs = nowMs() + MeshConfig.SESSION_LIFETIME_MS,
                    lastLocalExecutionState = null
                )
            }
            return null
        }

        val parsed = decodeState(sessionId, rawState, membership)
        if (parsed == null) {
            // Corrupt/torn record -> fail closed with generationUnknown = true (Section 5.3.7 & 16.2)
            return PersistedConvoyState(
                sessionId = sessionId,
                membership = membership,
                acceptedDecision = null,
                generationHighWater = 0L,
                generationUnknown = true,
                transitionTimestampsMs = emptyList(),
                expiresAtMs = nowMs() + MeshConfig.SESSION_LIFETIME_MS,
                lastLocalExecutionState = null
            )
        }
        if (nowMs() >= parsed.expiresAtMs) {
            backend.removeSync(keyForState(sessionId))
            backend.removeSync(keyForMembership(sessionId))
            return null
        }
        return parsed
    }

    override fun saveGenerationHighWaterWriteAhead(sessionId: UUID, highWater: Long): Boolean = synchronized(lock) {
        val existing = loadOrBlank(sessionId)
        val updated = existing.copy(
            generationHighWater = maxOf(existing.generationHighWater, highWater),
            generationUnknown = false,
            expiresAtMs = maxOf(existing.expiresAtMs, nowMs() + MeshConfig.SESSION_LIFETIME_MS)
        )
        backend.putStringSync(keyForState(sessionId), encodeState(updated))
    }

    override fun saveAcceptedDecisionAtomically(
        sessionId: UUID,
        decision: TransportDecision,
        highWater: Long
    ): Boolean = synchronized(lock) {
        val existing = loadOrBlank(sessionId)
        val updated = existing.copy(
            acceptedDecision = decision,
            generationHighWater = maxOf(existing.generationHighWater, highWater, decision.generation),
            generationUnknown = false,
            expiresAtMs = maxOf(existing.expiresAtMs, nowMs() + MeshConfig.SESSION_LIFETIME_MS)
        )
        backend.putStringSync(keyForState(sessionId), encodeState(updated))
    }

    override fun saveSnapshot(state: PersistedConvoyState): Boolean = synchronized(lock) {
        val okMem = state.membership?.let { mem ->
            backend.putStringSync(keyForMembership(state.sessionId), encodeMembership(mem))
        } ?: true
        val okState = backend.putStringSync(keyForState(state.sessionId), encodeState(state))
        okMem && okState
    }

    fun saveMembership(snapshot: ConvoyMembershipSnapshot): Boolean = synchronized(lock) {
        backend.putStringSync(keyForMembership(snapshot.sessionId), encodeMembership(snapshot))
    }

    override fun clear(sessionId: UUID?) {
        synchronized(lock) {
            if (sessionId == null) {
                backend.clearSync()
            } else {
                backend.removeSync(keyForState(sessionId))
                backend.removeSync(keyForMembership(sessionId))
            }
        }
    }

    private fun loadOrBlank(sessionId: UUID): PersistedConvoyState {
        val raw = backend.getString(keyForState(sessionId))
        val rawMem = backend.getString(keyForMembership(sessionId))
        val membership = rawMem?.let { decodeMembership(sessionId, it) }
        val parsed = raw?.let { decodeState(sessionId, it, membership) }
        return parsed ?: PersistedConvoyState(
            sessionId = sessionId,
            membership = membership,
            acceptedDecision = null,
            generationHighWater = 0L,
            generationUnknown = false,
            transitionTimestampsMs = emptyList(),
            expiresAtMs = nowMs() + MeshConfig.SESSION_LIFETIME_MS,
            lastLocalExecutionState = null
        )
    }

    companion object {
        private const val MAGIC_STATE = 0x43565301 // "CVS\1"
        private const val MAGIC_MEMBERSHIP = 0x43564D01 // "CVM\1"
        private val csprng = SecureRandom()

        fun generateSenderIncarnation(): Long = csprng.nextLong()

        private fun keyForState(sessionId: UUID): String = "convoy_state_$sessionId"
        private fun keyForMembership(sessionId: UUID): String = "convoy_mem_$sessionId"

        internal fun encodeState(state: PersistedConvoyState): String {
            val decBytes = state.acceptedDecision?.let { d ->
                ConvoyStateCodec.encode(ConvoyStateWirePayload(decision = d, records = emptyList()))
            } ?: ByteArray(0)
            val tsCount = state.transitionTimestampsMs.size.coerceAtMost(32)
            val bodyLen = 4 + 16 + 8 + 1 + 8 + 1 + 4 + decBytes.size + 1 + tsCount * 8
            val buf = ByteBuffer.allocate(bodyLen + 4).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(MAGIC_STATE)
            buf.putLong(state.sessionId.mostSignificantBits)
            buf.putLong(state.sessionId.leastSignificantBits)
            buf.putLong(state.generationHighWater)
            buf.put(if (state.generationUnknown) 1.toByte() else 0.toByte())
            buf.putLong(state.expiresAtMs)
            buf.put((state.lastLocalExecutionState?.ordinal ?: -1).toByte())
            buf.putInt(decBytes.size)
            buf.put(decBytes)
            buf.put(tsCount.toByte())
            for (i in 0 until tsCount) {
                buf.putLong(state.transitionTimestampsMs[i])
            }
            val crc = CRC32().apply { update(buf.array(), 0, bodyLen) }.value.toInt()
            buf.putInt(crc)
            return Base64.getEncoder().encodeToString(buf.array())
        }

        internal fun decodeState(
            expectedSessionId: UUID,
            encoded: String,
            membership: ConvoyMembershipSnapshot?
        ): PersistedConvoyState? {
            return try {
                val raw = Base64.getDecoder().decode(encoded)
                if (raw.size < 4 + 16 + 8 + 1 + 8 + 1 + 4 + 1 + 4) return null
                val bodyLen = raw.size - 4
                val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                val expectedCrc = ByteBuffer.wrap(raw, bodyLen, 4).order(ByteOrder.BIG_ENDIAN).int
                val actualCrc = CRC32().apply { update(raw, 0, bodyLen) }.value.toInt()
                if (actualCrc != expectedCrc) return null

                if (buf.int != MAGIC_STATE) return null
                val sid = UUID(buf.long, buf.long)
                if (sid != expectedSessionId) return null
                val highWater = buf.long
                val unknown = buf.get() != 0.toByte()
                val expiresAtMs = buf.long
                val execOrdinal = buf.get().toInt()
                val lastExec = if (execOrdinal in LocalTransportState.values().indices) {
                    LocalTransportState.values()[execOrdinal]
                } else null
                val decLen = buf.int
                if (decLen < 0 || decLen > 512 || buf.remaining() < decLen + 1 + 4) return null
                val decBytes = ByteArray(decLen).also { buf.get(it) }
                val accepted = if (decLen > 0) {
                    val meshCount = membership?.meshParticipants?.size ?: ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS
                    val res = ConvoyStateCodec.decode(decBytes, meshCount)
                    if (res is ConvoyStateDecodeResult.Ok) res.payload.decision else return null
                } else null
                val tsCount = buf.get().toInt() and 0xFF
                if (buf.remaining() != tsCount * 8 + 4) return null
                val timestamps = List(tsCount) { buf.long }
                PersistedConvoyState(
                    sessionId = sid,
                    membership = membership,
                    acceptedDecision = accepted,
                    generationHighWater = highWater,
                    generationUnknown = unknown,
                    transitionTimestampsMs = timestamps,
                    expiresAtMs = expiresAtMs,
                    lastLocalExecutionState = lastExec
                )
            } catch (_: Exception) {
                null
            }
        }

        internal fun encodeMembership(snapshot: ConvoyMembershipSnapshot): String {
            val encodedIds = snapshot.members.map { it.stableMemberId.toByteArray(Charsets.UTF_8) to it.role.wireCode }
            val bodyLen = 4 + 16 + 8 + 1 + encodedIds.sumOf { 2 + it.first.size + 1 }
            val buf = ByteBuffer.allocate(bodyLen + 4).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(MAGIC_MEMBERSHIP)
            buf.putLong(snapshot.sessionId.mostSignificantBits)
            buf.putLong(snapshot.sessionId.leastSignificantBits)
            buf.putLong(snapshot.localRevision)
            buf.put((encodedIds.size and 0xFF).toByte())
            for ((bytes, role) in encodedIds) {
                buf.putShort(bytes.size.toShort())
                buf.put(bytes)
                buf.put(role)
            }
            val crc = CRC32().apply { update(buf.array(), 0, bodyLen) }.value.toInt()
            buf.putInt(crc)
            return Base64.getEncoder().encodeToString(buf.array())
        }

        internal fun decodeMembership(expectedSessionId: UUID, encoded: String): ConvoyMembershipSnapshot? {
            return try {
                val raw = Base64.getDecoder().decode(encoded)
                if (raw.size < 4 + 16 + 8 + 1 + 4) return null
                val bodyLen = raw.size - 4
                val expectedCrc = ByteBuffer.wrap(raw, bodyLen, 4).order(ByteOrder.BIG_ENDIAN).int
                val actualCrc = CRC32().apply { update(raw, 0, bodyLen) }.value.toInt()
                if (actualCrc != expectedCrc) return null

                val buf = ByteBuffer.wrap(raw, 0, bodyLen).order(ByteOrder.BIG_ENDIAN)
                if (buf.int != MAGIC_MEMBERSHIP) return null
                val sid = UUID(buf.long, buf.long)
                if (sid != expectedSessionId) return null
                val localRevision = buf.long
                val count = buf.get().toInt() and 0xFF
                val members = ArrayList<ConvoyMember>(count)
                for (i in 0 until count) {
                    if (buf.remaining() < 3) return null
                    val idLen = buf.short.toInt() and 0xFFFF
                    if (buf.remaining() < idLen + 1) return null
                    val idBytes = ByteArray(idLen).also { buf.get(it) }
                    val roleCode = buf.get()
                    val role = MemberRole.fromWireCode(roleCode) ?: return null
                    members.add(ConvoyMember(String(idBytes, Charsets.UTF_8), role))
                }
                if (buf.remaining() != 0) return null
                ConvoyMembershipSnapshot.create(sid, members, localRevision)
            } catch (_: Exception) {
                null
            }
        }
    }
}

package com.ridervoice.mesh.identity

import android.content.Context
import java.util.UUID

/**
 * Stable mesh identity. [deviceId] is the permanent graph/audio/route identity;
 * IP addresses are only ever current endpoints and never identity.
 */
data class MeshIdentity(
    val deviceId: String,
    val displayName: String
)

/** Minimal key-value abstraction so identity logic is testable on the JVM. */
interface MeshKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemoryMeshKeyValueStore : MeshKeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * Plain (non-encrypted) private preferences in a dedicated file. Deliberately NOT SecurePreferences:
 * its clear() on sign-out would reset the mesh identity. The device id is not a secret.
 */
class SharedPrefsMeshKeyValueStore(context: Context) : MeshKeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("mesh_identity_prefs", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

class MeshIdentityRepository(
    private val store: MeshKeyValueStore,
    private val defaultDisplayName: () -> String,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    /** Returns the persisted identity, generating the device id exactly once. */
    fun get(): MeshIdentity {
        val id = store.getString(KEY_DEVICE_ID)?.takeIf { isValidDeviceId(it) }
            ?: idGenerator().also { store.putString(KEY_DEVICE_ID, it) }
        val name = store.getString(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: sanitizeName(defaultDisplayName())
        return MeshIdentity(id, name)
    }

    fun setDisplayName(name: String) {
        store.putString(KEY_DISPLAY_NAME, sanitizeName(name))
    }

    companion object {
        const val KEY_DEVICE_ID = "mesh_device_id"
        const val KEY_DISPLAY_NAME = "mesh_display_name"
        const val MAX_NAME_LENGTH = 24

        fun isValidDeviceId(id: String): Boolean = runCatching { UUID.fromString(id) }.isSuccess

        fun sanitizeName(raw: String): String {
            val cleaned = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME_LENGTH)
            return cleaned.ifBlank { "Rider" }
        }
    }
}


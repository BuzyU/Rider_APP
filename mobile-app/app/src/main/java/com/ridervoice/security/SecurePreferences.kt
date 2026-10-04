package com.ridervoice.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

class SecurePreferences(private val context: Context) {

    private val prefs: SharedPreferences = createSafePreferences(context)

    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_FILE = "secure_prefs"
        private const val FALLBACK_PREFS_FILE = "secure_prefs_unencrypted_fallback"

        private fun createSafePreferences(context: Context): SharedPreferences {
            return try {
                createEncryptedPrefs(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences. Attempting self-healing recovery: ${e.message}", e)
                try {
                    // Self-healing step 1: Delete corrupted preferences file
                    val prefsFile = File(context.filesDir.parent, "shared_prefs/${PREFS_FILE}.xml")
                    if (prefsFile.exists()) {
                        prefsFile.delete()
                    }

                    // Self-healing step 2: Clear AndroidKeyStore master key entry if accessible
                    try {
                        val keyStore = KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)
                        keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    } catch (keystoreEx: Throwable) {
                        Log.w(TAG, "Could not delete MasterKey from KeyStore: ${keystoreEx.message}")
                    }

                    createEncryptedPrefs(context)
                } catch (recoveryEx: Throwable) {
                    Log.e(TAG, "Self-healing recovery failed. Falling back to standard SharedPreferences: ${recoveryEx.message}", recoveryEx)
                    context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
                }
            }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun saveToken(token: String) {
        runCatching { prefs.edit().putString("token", token).apply() }
    }

    fun getToken(): String? {
        return runCatching { prefs.getString("token", null) }.getOrNull()
    }

    fun saveRoomName(roomName: String) {
        runCatching { prefs.edit().putString("last_room", roomName).apply() }
    }

    fun getLastRoomName(): String? {
        return runCatching { prefs.getString("last_room", null) }.getOrNull()
    }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    fun saveDeviceConfigured(configured: Boolean) {
        runCatching { prefs.edit().putBoolean("device_configured", configured).apply() }
    }

    fun isDeviceConfigured(): Boolean {
        return runCatching { prefs.getBoolean("device_configured", false) }.getOrDefault(false)
    }

    fun saveDeviceName(name: String) {
        runCatching { prefs.edit().putString("device_name", name).apply() }
    }

    fun getDeviceName(): String? {
        return runCatching { prefs.getString("device_name", null) }.getOrNull()
    }

    fun saveString(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).apply() }
    }

    fun getString(key: String, defaultValue: String): String {
        return runCatching { prefs.getString(key, defaultValue) ?: defaultValue }.getOrDefault(defaultValue)
    }

    fun saveBoolean(key: String, value: Boolean) {
        runCatching { prefs.edit().putBoolean(key, value).apply() }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return runCatching { prefs.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)
    }

    fun saveActiveRide(roomName: String, isHost: Boolean) {
        runCatching {
            prefs.edit()
                .putString("active_ride_room", roomName)
                .putBoolean("active_ride_is_host", isHost)
                .apply()
        }
    }

    fun getActiveRideRoom(): String? {
        return runCatching { prefs.getString("active_ride_room", null) }.getOrNull()
    }

    fun isActiveRideHost(): Boolean {
        return runCatching { prefs.getBoolean("active_ride_is_host", false) }.getOrDefault(false)
    }

    fun clearActiveRide() {
        runCatching {
            prefs.edit()
                .remove("active_ride_room")
                .remove("active_ride_is_host")
                .apply()
        }
    }
}

package com.ridervoice.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

open class SecurePreferences(private val context: Context? = null) {

    private val memoryStore = mutableMapOf<String, Any?>()
    private val prefs: SharedPreferences? = context?.let { createSafePreferences(it) }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_FILE = "secure_prefs"
        private const val FALLBACK_PREFS_FILE = "secure_prefs_unencrypted_fallback"
        const val KEY_NETWORK_DIAGNOSTICS = "network_diagnostics_enabled"
        const val KEY_SHARE_DIAGNOSTICS_HOST_ENABLED = "share_diagnostics_host_enabled"
        const val KEY_DIAGNOSTICS_CONSENT_SHOWN = "diagnostics_consent_shown"
        const val KEY_APP_LANGUAGE = "app_language"

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

    open fun saveToken(token: String) {
        val p = prefs ?: run { memoryStore["token"] = token; return }
        runCatching { p.edit().putString("token", token).apply() }
    }

    open fun getToken(): String? {
        val p = prefs ?: return memoryStore["token"] as? String
        return runCatching { p.getString("token", null) }.getOrNull()
    }

    open fun saveRoomName(roomName: String) {
        val p = prefs ?: run { memoryStore["last_room"] = roomName; return }
        runCatching { p.edit().putString("last_room", roomName).apply() }
    }

    open fun getLastRoomName(): String? {
        val p = prefs ?: return memoryStore["last_room"] as? String
        return runCatching { p.getString("last_room", null) }.getOrNull()
    }

    open fun clear() {
        val lang = getAppLanguage()
        val p = prefs ?: run {
            memoryStore.clear()
            memoryStore[KEY_APP_LANGUAGE] = lang
            return
        }
        runCatching {
            p.edit().clear().putString(KEY_APP_LANGUAGE, lang).apply()
        }
    }

    open fun saveAppLanguage(lang: String) {
        val sanitized = when (lang.lowercase()) {
            "en", "hi", "mr" -> lang.lowercase()
            else -> "system"
        }
        saveString(KEY_APP_LANGUAGE, sanitized)
        _appLanguage.value = sanitized
    }

    open fun getAppLanguage(): String {
        return getString(KEY_APP_LANGUAGE, "system")
    }

    open fun saveDeviceConfigured(configured: Boolean) {
        saveBoolean("device_configured", configured)
    }

    open fun isDeviceConfigured(): Boolean {
        return getBoolean("device_configured", false)
    }

    open fun saveDeviceName(name: String) {
        saveString("device_name", name)
    }

    open fun getDeviceName(): String? {
        val p = prefs ?: return memoryStore["device_name"] as? String
        return runCatching { p.getString("device_name", null) }.getOrNull()
    }

    open fun saveString(key: String, value: String) {
        val p = prefs ?: run { memoryStore[key] = value; return }
        runCatching { p.edit().putString(key, value).apply() }
    }

    open fun getString(key: String, defaultValue: String): String {
        val p = prefs ?: return (memoryStore[key] as? String) ?: defaultValue
        return runCatching { p.getString(key, defaultValue) ?: defaultValue }.getOrDefault(defaultValue)
    }

    open fun saveBoolean(key: String, value: Boolean) {
        val p = prefs ?: run { memoryStore[key] = value; return }
        runCatching { p.edit().putBoolean(key, value).apply() }
    }

    open fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val p = prefs ?: return (memoryStore[key] as? Boolean) ?: defaultValue
        return runCatching { p.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)
    }

    open fun saveActiveRide(roomName: String, isHost: Boolean, lkRoomName: String? = null) {
        saveString("active_ride_room", roomName)
        saveBoolean("active_ride_is_host", isHost)
        if (lkRoomName != null) {
            saveString("active_ride_lk_room", lkRoomName)
        } else {
            val p = prefs ?: run { memoryStore.remove("active_ride_lk_room"); return }
            runCatching { p.edit().remove("active_ride_lk_room").apply() }
        }
    }

    open fun getActiveRideRoom(): String? {
        val p = prefs ?: return memoryStore["active_ride_room"] as? String
        return runCatching { p.getString("active_ride_room", null) }.getOrNull()
    }

    open fun getActiveRideLkRoom(): String? {
        val p = prefs ?: return memoryStore["active_ride_lk_room"] as? String
        return runCatching { p.getString("active_ride_lk_room", null) }.getOrNull()
    }

    open fun isActiveRideHost(): Boolean {
        return getBoolean("active_ride_is_host", false)
    }

    open fun clearActiveRide() {
        val p = prefs ?: run {
            memoryStore.remove("active_ride_room")
            memoryStore.remove("active_ride_is_host")
            memoryStore.remove("active_ride_lk_room")
            return
        }
        runCatching {
            p.edit()
                .remove("active_ride_room")
                .remove("active_ride_is_host")
                .remove("active_ride_lk_room")
                .apply()
        }
    }

    private val _networkDiagnosticsEnabled = MutableStateFlow(isNetworkDiagnosticsEnabled())
    val networkDiagnosticsEnabled: StateFlow<Boolean> = _networkDiagnosticsEnabled.asStateFlow()

    private val _shareDiagnosticsWhenHostEnabled = MutableStateFlow(isShareDiagnosticsWhenHostEnabled())
    val shareDiagnosticsWhenHostEnabled: StateFlow<Boolean> = _shareDiagnosticsWhenHostEnabled.asStateFlow()

    private val _appLanguage = MutableStateFlow(getAppLanguage())
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_NETWORK_DIAGNOSTICS) {
            _networkDiagnosticsEnabled.value = isNetworkDiagnosticsEnabled()
        }
        if (key == KEY_SHARE_DIAGNOSTICS_HOST_ENABLED) {
            _shareDiagnosticsWhenHostEnabled.value = isShareDiagnosticsWhenHostEnabled()
        }
        if (key == KEY_APP_LANGUAGE) {
            _appLanguage.value = getAppLanguage()
        }
    }

    init {
        try {
            prefs?.registerOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register OnSharedPreferenceChangeListener: ${e.message}")
        }
    }

    fun isNetworkDiagnosticsEnabled(): Boolean {
        return getBoolean(KEY_NETWORK_DIAGNOSTICS, false)
    }

    fun setNetworkDiagnosticsEnabled(enabled: Boolean) {
        saveBoolean(KEY_NETWORK_DIAGNOSTICS, enabled)
        _networkDiagnosticsEnabled.value = enabled
    }

    fun isShareDiagnosticsWhenHostEnabled(): Boolean {
        return getBoolean(KEY_SHARE_DIAGNOSTICS_HOST_ENABLED, true)
    }

    fun setShareDiagnosticsWhenHostEnabled(enabled: Boolean) {
        saveBoolean(KEY_SHARE_DIAGNOSTICS_HOST_ENABLED, enabled)
        _shareDiagnosticsWhenHostEnabled.value = enabled
    }

    fun isDiagnosticsConsentShown(): Boolean {
        return getBoolean(KEY_DIAGNOSTICS_CONSENT_SHOWN, false)
    }

    fun setDiagnosticsConsentShown(shown: Boolean) {
        saveBoolean(KEY_DIAGNOSTICS_CONSENT_SHOWN, shown)
    }

    open fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs?.registerOnSharedPreferenceChangeListener(listener)
    }

    open fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

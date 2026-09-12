package com.ridervoice.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("token", null)
    }

    fun saveRoomName(roomName: String) {
        prefs.edit().putString("last_room", roomName).apply()
    }

    fun getLastRoomName(): String? {
        return prefs.getString("last_room", null)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun saveDeviceConfigured(configured: Boolean) {
        prefs.edit().putBoolean("device_configured", configured).apply()
    }

    fun isDeviceConfigured(): Boolean {
        return prefs.getBoolean("device_configured", false)
    }

    fun saveDeviceName(name: String) {
        prefs.edit().putString("device_name", name).apply()
    }

    fun getDeviceName(): String? {
        return prefs.getString("device_name", null)
    }

    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun saveActiveRide(roomName: String, isHost: Boolean) {
        prefs.edit()
            .putString("active_ride_room", roomName)
            .putBoolean("active_ride_is_host", isHost)
            .apply()
    }

    fun getActiveRideRoom(): String? {
        return prefs.getString("active_ride_room", null)
    }

    fun isActiveRideHost(): Boolean {
        return prefs.getBoolean("active_ride_is_host", false)
    }

    fun clearActiveRide() {
        prefs.edit()
            .remove("active_ride_room")
            .remove("active_ride_is_host")
            .apply()
    }
}

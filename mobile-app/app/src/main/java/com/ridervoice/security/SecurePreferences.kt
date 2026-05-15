package com.ridervoice.security

import android.content.Context

class SecurePreferences(context: Context) {

    private val prefs =
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("token", null)
    }
}

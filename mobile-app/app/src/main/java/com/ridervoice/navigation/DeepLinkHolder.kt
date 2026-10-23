package com.ridervoice.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DeepLinkHolder {
    private val _pendingToken = MutableStateFlow<String?>(null)
    val pendingToken: StateFlow<String?> = _pendingToken.asStateFlow()

    fun set(token: String?) {
        _pendingToken.value = token
    }

    fun consume(): String? {
        val t = _pendingToken.value
        _pendingToken.value = null
        return t
    }

    fun parseDeepLinkToken(uri: Uri?): String? {
        if (uri == null) return null
        return parseDeepLinkToken(uri.toString())
    }

    fun parseDeepLinkToken(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val clean = rawUrl.trim()
        return when {
            clean.startsWith("ridervoice://join/") -> {
                clean.substringAfter("ridervoice://join/").substringBefore("?").substringBefore("#").trim().ifBlank { null }
            }
            clean.startsWith("ridervoice://join") -> {
                val tokenQuery = clean.substringAfter("token=", "")
                if (tokenQuery.isNotBlank()) tokenQuery.substringBefore("&").substringBefore("#").trim().ifBlank { null } else null
            }
            clean.contains("/j/") -> {
                clean.substringAfter("/j/").substringBefore("?").substringBefore("#").trim().ifBlank { null }
            }
            clean.contains("/join/") -> {
                clean.substringAfter("/join/").substringBefore("?").substringBefore("#").trim().ifBlank { null }
            }
            else -> null
        }
    }
}

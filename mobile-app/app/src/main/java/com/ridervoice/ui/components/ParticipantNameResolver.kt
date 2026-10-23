package com.ridervoice.ui.components

import java.util.concurrent.ConcurrentHashMap

object ParticipantNameResolver {
    private val cachedNames = ConcurrentHashMap<String, String>()

    fun register(userId: String, callsign: String) {
        if (userId.isNotBlank() && callsign.isNotBlank()) {
            cachedNames[userId] = callsign
        }
    }

    fun resolve(identity: String, livekitName: String? = null, isCurrentUser: Boolean = false): String {
        if (isCurrentUser) return "You"
        if (!livekitName.isNullOrBlank() && livekitName != identity) {
            return livekitName.trim()
        }
        val cached = cachedNames[identity]
        if (!cached.isNullOrBlank()) {
            return cached.trim()
        }
        val suffix = if (identity.length >= 4) identity.takeLast(4) else identity
        return "Rider $suffix"
    }

    fun clear() {
        cachedNames.clear()
    }
}

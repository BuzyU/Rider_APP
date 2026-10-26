package com.ridervoice.ui.components

import java.util.concurrent.ConcurrentHashMap

object ParticipantNameResolver {
    // Ride-keyed callsign caches (F-17)
    private val rideCaches = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val globalCache = ConcurrentHashMap<String, String>()

    // Deterministic numbered fallbacks per ride (e.g. "Rider 1", "Rider 2", never raw UIDs)
    private val rideNumberMaps = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val globalNumberMap = ConcurrentHashMap<String, Int>()

    fun register(userId: String, callsign: String, rideId: String? = null) {
        if (userId.isBlank() || callsign.isBlank()) return
        if (!rideId.isNullOrBlank()) {
            rideCaches.computeIfAbsent(rideId) { ConcurrentHashMap() }[userId] = callsign.trim()
        } else {
            globalCache[userId] = callsign.trim()
        }
    }

    /**
     * Fallback chain:
     * 1. If isCurrentUser: return youLabel ("You" / localized equivalent)
     * 2. Ride-specific callsign cache
     * 3. Global callsign cache
     * 4. Display name from LiveKit (if present and not equal to raw identity)
     * 5. Numbered fallback "$riderPrefix $N" (never exposes raw UID characters)
     */
    fun resolve(
        identity: String,
        livekitName: String? = null,
        isCurrentUser: Boolean = false,
        rideId: String? = null,
        youLabel: String = "You",
        riderPrefix: String = "Rider"
    ): String {
        if (isCurrentUser) return youLabel

        // 1. Check ride-specific cache
        if (!rideId.isNullOrBlank()) {
            val rideMap = rideCaches[rideId]
            val callsign = rideMap?.get(identity)
            if (!callsign.isNullOrBlank()) return callsign
        }

        // 2. Check global cache
        val globalCallsign = globalCache[identity]
        if (!globalCallsign.isNullOrBlank()) return globalCallsign

        // 3. Check LiveKit display name (if not raw identity)
        if (!livekitName.isNullOrBlank() && livekitName != identity) {
            return livekitName.trim()
        }

        // 4. Clean numbered fallback (never raw UID)
        val numMap = if (!rideId.isNullOrBlank()) {
            rideNumberMaps.computeIfAbsent(rideId) { ConcurrentHashMap() }
        } else {
            globalNumberMap
        }
        val number = numMap.computeIfAbsent(identity) { numMap.size + 1 }
        return "$riderPrefix $number"
    }

    fun clearRide(rideId: String) {
        rideCaches.remove(rideId)
        rideNumberMaps.remove(rideId)
    }

    fun clear() {
        rideCaches.clear()
        globalCache.clear()
        rideNumberMaps.clear()
        globalNumberMap.clear()
    }
}

package com.ridervoice.utils

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

/**
 * Pure decoder and validator for LiveKit JWT tokens.
 * LiveKit server SDK issues tokens with payload:
 * {
 *   "video": {
 *     "room": "convoy-name",
 *     "roomJoin": true
 *   }
 * }
 */
object JwtRoomClaimDecoder {

    /**
     * Extracts the target room name encoded inside the JWT payload.
     * Returns null if token is null, malformed, lacks payload, or lacks video.room.
     */
    fun extractRoomName(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size < 2) return null

        return try {
            val payloadBase64 = parts[1]
            val cleanBase64 = payloadBase64
                .replace('-', '+')
                .replace('_', '/')
            val padded = when (cleanBase64.length % 4) {
                2 -> "$cleanBase64=="
                3 -> "$cleanBase64="
                else -> cleanBase64
            }
            val decodedBytes = java.util.Base64.getDecoder().decode(padded)
            val jsonStr = String(decodedBytes, StandardCharsets.UTF_8)
            val jsonElement = JsonParser.parseString(jsonStr)
            if (!jsonElement.isJsonObject) return null
            val jsonObj = jsonElement.asJsonObject

            if (jsonObj.has("video") && jsonObj.get("video").isJsonObject) {
                val video = jsonObj.getAsJsonObject("video")
                if (video.has("room") && !video.get("room").isJsonNull) {
                    val r = video.get("room").asString
                    if (r.isNotBlank()) return r
                }
            }
            if (jsonObj.has("room") && !jsonObj.get("room").isJsonNull) {
                val r = jsonObj.get("room").asString
                if (r.isNotBlank()) return r
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validates that the token has a valid room claim that strictly matches expectedRoom.
     * Returns true only when token is well-formed and video.room == expectedRoom.
     */
    fun validateRoom(token: String?, expectedRoom: String?): Boolean {
        if (token.isNullOrBlank() || expectedRoom.isNullOrBlank()) return false
        val claim = extractRoomName(token) ?: return false
        return claim == expectedRoom
    }
}

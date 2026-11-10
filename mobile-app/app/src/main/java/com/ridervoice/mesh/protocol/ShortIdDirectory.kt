package com.ridervoice.mesh.protocol

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps 4-byte short device IDs to full 16-byte UUID device ID strings.
 *
 * Populated from local identity on startup and from authenticated HELLO payloads.
 * Detects 32-bit short ID collisions across convoy members and raises diagnostic flags.
 */
class ShortIdDirectory {
    private val shortToFull = ConcurrentHashMap<Int, String>()
    private var selfDeviceId: String? = null
    private var selfShortId: Int? = null

    val collisionCount = AtomicLong(0L)

    fun registerSelf(deviceId: String): Int {
        val short = shortIdOf(deviceId)
        selfDeviceId = deviceId
        selfShortId = short
        shortToFull[short] = deviceId
        return short
    }

    fun resolveSelfShort(): Int? = selfShortId

    fun resolveSelfFull(): String? = selfDeviceId

    fun register(deviceId: String): Int {
        val short = shortIdOf(deviceId)
        val existing = shortToFull[short]
        if (existing != null && existing != deviceId) {
            collisionCount.incrementAndGet()
            return short
        }
        shortToFull[short] = deviceId
        return short
    }

    /**
     * Registers a device ID from an authenticated HELLO payload.
     * Returns true if registered or already present; returns false on collision or short ID mismatch.
     */
    fun registerHello(fullDeviceId: String, srcShort: Int): Boolean {
        val derivedShort = shortIdOf(fullDeviceId)
        if (derivedShort != srcShort) {
            return false
        }
        val existing = shortToFull[srcShort]
        if (existing != null && existing != fullDeviceId) {
            collisionCount.incrementAndGet()
            return false
        }
        shortToFull[srcShort] = fullDeviceId
        return true
    }

    fun resolve(shortId: Int): String? = shortToFull[shortId]

    fun contains(shortId: Int): Boolean = shortToFull.containsKey(shortId)

    fun hasCollision(): Boolean = collisionCount.get() > 0L

    fun clear() {
        shortToFull.clear()
        selfDeviceId = null
        selfShortId = null
        collisionCount.set(0L)
    }

    companion object {
        fun shortIdOf(deviceId: String): Int {
            val u = UUID.fromString(deviceId)
            return (u.mostSignificantBits ushr 32).toInt()
        }
    }
}


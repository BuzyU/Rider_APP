package com.ridervoice.mesh.switching

/**
 * Clock interface providing milliseconds epoch timestamp for switching,
 * anti-flapping timing, and probe throttling.
 */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}


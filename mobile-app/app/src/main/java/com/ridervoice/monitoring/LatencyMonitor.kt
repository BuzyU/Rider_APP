package com.ridervoice.monitoring

class LatencyMonitor {

    fun checkLatency(ms: Int): String {

        return when {
            ms < 100 -> "Excellent"
            ms < 200 -> "Good"
            ms < 400 -> "Weak"
            else -> "Poor"
        }
    }
}

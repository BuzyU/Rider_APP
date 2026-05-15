package com.ridervoice.monitoring

class ConnectionQualityMonitor {

    fun getQuality(latency: Int, packetLoss: Float): String {

        return when {
            latency < 100 && packetLoss < 1f -> "Excellent"
            latency < 200 && packetLoss < 3f -> "Good"
            latency < 400 && packetLoss < 5f -> "Weak"
            else -> "Poor"
        }
    }
}

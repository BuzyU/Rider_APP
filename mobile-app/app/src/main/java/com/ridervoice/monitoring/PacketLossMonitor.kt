package com.ridervoice.monitoring

class PacketLossMonitor {

    fun calculate(loss: Float): String {

        return when {
            loss < 1f -> "Stable"
            loss < 5f -> "Moderate"
            else -> "Critical"
        }
    }
}

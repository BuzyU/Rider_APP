package com.ridervoice.services

import com.google.android.gms.location.Priority

enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }

object LocationPolicy {
    fun intervalMs(
        speedKmh: Float,
        stationarySec: Int,
        thermal: ThermalLevel,
        batteryPct: Int,
        charging: Boolean
    ): Long {
        val speedTier = when {
            speedKmh < 3 && stationarySec > 60 -> 30_000L
            speedKmh < 15 -> 10_000L
            speedKmh < 60 -> 5_000L
            else -> 3_000L
        }
        val thermalTier = when (thermal) {
            ThermalLevel.NORMAL -> 0L
            ThermalLevel.WARM -> 10_000L
            ThermalLevel.HOT -> 20_000L
            ThermalLevel.CRITICAL -> 30_000L
        }
        val batteryTier = if (!charging && batteryPct < 15) 15_000L else 0L
        return maxOf(speedTier, thermalTier, batteryTier)
    }

    fun priority(speedKmh: Float, thermal: ThermalLevel): Int {
        return if (thermal >= ThermalLevel.HOT || speedKmh < 15f) {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        } else {
            Priority.PRIORITY_HIGH_ACCURACY
        }
    }
}

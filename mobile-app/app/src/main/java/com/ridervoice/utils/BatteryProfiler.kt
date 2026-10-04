package com.ridervoice.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatterySessionReport(
    val startPct: Int,
    val endPct: Int,
    val deltaPct: Int,
    val durationMs: Long,
    val drainRatePerHour: Float,
    val peakTempCelsius: Float,
    val isPlugged: Boolean
)

class BatteryProfiler(private val context: Context) {
    private var startPct: Int = 0
    private var startTimeMs: Long = 0L
    private var peakTemp: Float = 0f
    private var isPluggedInitial: Boolean = false

    fun startSession() {
        startTimeMs = System.currentTimeMillis()
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startPct = getBatteryLevel(batteryIntent)
        peakTemp = getBatteryTemperature(batteryIntent)
        isPluggedInitial = getIsPlugged(batteryIntent)
    }

    fun stopSession(): BatterySessionReport {
        val endTimeMs = System.currentTimeMillis()
        val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1_000L)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val endPct = getBatteryLevel(batteryIntent)
        val currentTemp = getBatteryTemperature(batteryIntent)
        if (currentTemp > peakTemp) peakTemp = currentTemp

        val deltaPct = startPct - endPct
        val hours = durationMs / 3_600_000f
        val drainRate = if (hours > 0f) deltaPct / hours else 0f

        return BatterySessionReport(
            startPct = startPct,
            endPct = endPct,
            deltaPct = deltaPct,
            durationMs = durationMs,
            drainRatePerHour = drainRate,
            peakTempCelsius = peakTemp,
            isPlugged = isPluggedInitial
        )
    }

    private fun getBatteryLevel(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    private fun getBatteryTemperature(intent: Intent?): Float {
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f // Android reports tenths of a degree Celsius
    }

    private fun getIsPlugged(intent: Intent?): Boolean {
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
               plugged == BatteryManager.BATTERY_PLUGGED_USB ||
               plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }
}

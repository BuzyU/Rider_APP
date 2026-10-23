package com.ridervoice.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ThermalState {
    NORMAL,
    LEVEL_1_WARM,
    LEVEL_2_HOT,
    LEVEL_3_CRITICAL
}

@Singleton
class ThermalManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ThermalManager"
        private const val CHECK_INTERVAL_MS = 15_000L
    }

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _thermalLevel = MutableStateFlow(ThermalLevel.NORMAL)
    val thermalLevel: StateFlow<ThermalLevel> = _thermalLevel.asStateFlow()

    private var monitorJob: Job? = null

    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val tempCelsius = readBatteryTemperature()
                val newState = when {
                    tempCelsius >= 45f -> ThermalState.LEVEL_3_CRITICAL
                    tempCelsius >= 41f -> ThermalState.LEVEL_2_HOT
                    tempCelsius >= 38f -> ThermalState.LEVEL_1_WARM
                    else               -> ThermalState.NORMAL
                }

                val newLevel = when (newState) {
                    ThermalState.NORMAL -> ThermalLevel.NORMAL
                    ThermalState.LEVEL_1_WARM -> ThermalLevel.WARM
                    ThermalState.LEVEL_2_HOT -> ThermalLevel.HOT
                    ThermalState.LEVEL_3_CRITICAL -> ThermalLevel.CRITICAL
                }

                if (_thermalState.value != newState) {
                    Log.w(TAG, "Thermal state: ${_thermalState.value} -> $newState (${tempCelsius}°C)")
                    _thermalState.value = newState
                    _thermalLevel.value = newLevel
                    com.ridervoice.utils.RvLog.loc("thermal=$newLevel temp=${tempCelsius}C")
                }

                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun readBatteryTemperature(): Float {
        return try {
            val intent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }

            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (raw < 0) 25f
            else raw / 10.0f
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery temperature: ${e.message}")
            25f
        }
    }
}

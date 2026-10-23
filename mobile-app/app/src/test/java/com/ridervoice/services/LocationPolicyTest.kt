package com.ridervoice.services

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPolicyTest {

    @Test
    fun stationarySpeedProduces30SecondInterval() {
        val interval = LocationPolicy.intervalMs(
            speedKmh = 1.0f,
            stationarySec = 65,
            thermal = ThermalLevel.NORMAL,
            batteryPct = 80,
            charging = true
        )
        assertEquals(30_000L, interval)
    }

    @Test
    fun highSpeedProduces3SecondInterval() {
        val interval = LocationPolicy.intervalMs(
            speedKmh = 80.0f,
            stationarySec = 0,
            thermal = ThermalLevel.NORMAL,
            batteryPct = 80,
            charging = true
        )
        assertEquals(3_000L, interval)
    }

    @Test
    fun thermalHotScalesIntervalUp() {
        val interval = LocationPolicy.intervalMs(
            speedKmh = 80.0f,
            stationarySec = 0,
            thermal = ThermalLevel.HOT,
            batteryPct = 80,
            charging = true
        )
        assertEquals(20_000L, interval)
    }

    @Test
    fun lowBatteryWhenUnpluggedScalesIntervalToAtLeast15Seconds() {
        val interval = LocationPolicy.intervalMs(
            speedKmh = 70.0f,
            stationarySec = 0,
            thermal = ThermalLevel.NORMAL,
            batteryPct = 10,
            charging = false
        )
        assertEquals(15_000L, interval)
    }

    @Test
    fun prioritySwitchesToBalancedWhenHotOrSlow() {
        assertEquals(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LocationPolicy.priority(speedKmh = 10.0f, thermal = ThermalLevel.NORMAL)
        )
        assertEquals(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LocationPolicy.priority(speedKmh = 70.0f, thermal = ThermalLevel.HOT)
        )
        assertEquals(
            Priority.PRIORITY_HIGH_ACCURACY,
            LocationPolicy.priority(speedKmh = 70.0f, thermal = ThermalLevel.NORMAL)
        )
    }
}

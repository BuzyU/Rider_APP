package com.ridervoice.mesh.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface WifiCapabilityChecker {
    fun isWifiHardwareAvailable(): Boolean
}

@Singleton
class SystemWifiCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : WifiCapabilityChecker {
    override fun isWifiHardwareAvailable(): Boolean {
        val wm = runCatching { context.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()
        val hasFeature = runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) }.getOrDefault(false)
        return wm != null && hasFeature
    }
}


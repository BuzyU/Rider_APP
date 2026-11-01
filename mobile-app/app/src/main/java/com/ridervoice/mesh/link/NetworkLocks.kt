package com.ridervoice.mesh.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Manages Wi-Fi and Multicast locks required for low-latency mesh communication.
 * Low-latency locks on Android 10+ only take full effect when the app is in the foreground.
 */
interface NetworkLocks {
    fun acquire()
    fun release()
}

object NoNetworkLocks : NetworkLocks {
    override fun acquire() {}
    override fun release() {}
}

class AndroidNetworkLocks(context: Context) : NetworkLocks {
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null

    @Synchronized
    override fun acquire() {
        if (multicast == null && wm != null) {
            multicast = wm.createMulticastLock("rider-mesh-mc").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifi == null && wm != null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifi = wm.createWifiLock(mode, "rider-mesh-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    override fun release() {
        runCatching { multicast?.release() }
        multicast = null
        runCatching { wifi?.release() }
        wifi = null
    }
}


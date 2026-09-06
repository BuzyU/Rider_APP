package com.ridervoice.permissions

import android.Manifest
import android.os.Build

object PermissionManager {
    // Requested at launch: Voice and Bluetooth
    val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    // Requested at point-of-use (Ride/Room start): Location and Notifications
    val ridePermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

    val backgroundLocationPermission: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION
}


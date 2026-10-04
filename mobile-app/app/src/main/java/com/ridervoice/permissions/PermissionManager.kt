package com.ridervoice.permissions

import android.Manifest
import android.os.Build

object PermissionManager {

    val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                list.add(Manifest.permission.BLUETOOTH)
                list.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

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

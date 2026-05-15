package com.ridervoice.permissions

import android.Manifest

object PermissionManager {

    val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.INTERNET
    )
}

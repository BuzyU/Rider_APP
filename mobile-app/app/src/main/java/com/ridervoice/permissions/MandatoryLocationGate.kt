package com.ridervoice.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

enum class LocationGateStatus {
    READY,
    MISSING_PERMISSION,
    LOCATION_SERVICES_OFF
}

/**
 * Enforces compulsory runtime location permission and active GPS/location services
 * before allowing riders to create or join a ride room.
 */
object MandatoryLocationGate {

    fun evaluate(context: Context): LocationGateStatus {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val locationEnabled = try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm != null && LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            false
        }

        return evaluateStatus(hasPermission = hasPermission, locationEnabled = locationEnabled)
    }

    @VisibleForTesting
    fun evaluateStatus(hasPermission: Boolean, locationEnabled: Boolean): LocationGateStatus {
        return when {
            !hasPermission -> LocationGateStatus.MISSING_PERMISSION
            !locationEnabled -> LocationGateStatus.LOCATION_SERVICES_OFF
            else -> LocationGateStatus.READY
        }
    }

    fun buildLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

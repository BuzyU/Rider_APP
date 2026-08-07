package com.ridervoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.ridervoice.navigation.NavGraph
import com.ridervoice.permissions.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePreferences: com.ridervoice.security.SecurePreferences

    // FIX: request required permissions at startup
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            // Could show a dialog explaining why permissions are needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request RECORD_AUDIO + BLUETOOTH_CONNECT before entering the app
        permissionLauncher.launch(PermissionManager.requiredPermissions)

        // Restore saved theme choice. Defaults to LIGHT — never reads system dark mode.
        com.ridervoice.ui.theme.ThemeState.set(securePreferences.getBoolean("dark_theme_enabled", false))

        setContent {
            com.ridervoice.ui.theme.RiderVoiceTheme {
                NavGraph()
            }
        }
    }
}

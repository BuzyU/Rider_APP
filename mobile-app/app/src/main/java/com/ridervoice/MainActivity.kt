package com.ridervoice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.ridervoice.navigation.NavGraph
import com.ridervoice.permissions.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePreferences: com.ridervoice.security.SecurePreferences

    private val showPermissionRationale = mutableStateOf(false)

    // Request required permissions at startup
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showPermissionRationale.value = true
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

                if (showPermissionRationale.value) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale.value = false },
                        title = { Text("Permissions Required") },
                        text = {
                            Text("RiderVoice requires Microphone and Bluetooth permissions to enable helmet intercom and wireless PTT functionality. Please grant them in Settings.")
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPermissionRationale.value = false
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }) {
                                Text("Open Settings")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionRationale.value = false }) {
                                Text("Continue Anyway")
                            }
                        }
                    )
                }
            }
        }
    }
}


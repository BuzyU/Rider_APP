package com.ridervoice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridervoice.navigation.NavGraph
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.ui.theme.*
import com.ridervoice.update.AppVersion
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePreferences: com.ridervoice.security.SecurePreferences

    private val showPermissionRationale = mutableStateOf(false)

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

        permissionLauncher.launch(PermissionManager.requiredPermissions)

        com.ridervoice.ui.theme.ThemeState.set(securePreferences.getBoolean("dark_theme_enabled", false))

        val uriData = intent?.data
        val initialRoute = if (uriData != null && uriData.scheme == "ridervoice" && uriData.host == "join") {
            val token = uriData.pathSegments.firstOrNull()
                ?: uriData.getQueryParameter("token")
                ?: uriData.lastPathSegment
            if (!token.isNullOrBlank()) {
                com.ridervoice.navigation.Routes.joinViaTokenPath(token)
            } else {
                intent?.getStringExtra("NAV_ROUTE")
            }
        } else {
            intent?.getStringExtra("NAV_ROUTE")
        }

        setContent {
            com.ridervoice.ui.theme.RiderVoiceTheme {
                val showUpdateSuccessDialog = remember { mutableStateOf(false) }
                val updatedVersionName = remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    val lastRecordedVersion = securePreferences.getString("installed_version_name", "")
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (lastRecordedVersion.isNotBlank()) {
                        val lastVer = AppVersion.parse(lastRecordedVersion)
                        val currentVer = AppVersion.parse(currentVersion)
                        if (currentVer > lastVer) {
                            updatedVersionName.value = currentVersion
                            showUpdateSuccessDialog.value = true
                        }
                    }

                    securePreferences.saveString("installed_version_name", currentVersion)
                }

                NavGraph(startRoute = initialRoute)

                if (showUpdateSuccessDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showUpdateSuccessDialog.value = false },
                        containerColor = DarkSlate,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = TechGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "UPDATE COMPLETE",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = "RiderVoice has been successfully updated to v${updatedVersionName.value}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { showUpdateSuccessDialog.value = false },
                                colors = ButtonDefaults.buttonColors(containerColor = TechGreen)
                            ) {
                                Text("CONTINUE", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

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

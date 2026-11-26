package com.ridervoice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.navigation.NavGraph
import androidx.activity.enableEdgeToEdge
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.ui.theme.*
import com.ridervoice.update.AppVersion
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePreferences: com.ridervoice.security.SecurePreferences
    @Inject lateinit var appEvents: com.ridervoice.state.AppEvents
    @Inject lateinit var apiService: com.ridervoice.network.ApiService

    private val showPermissionRationale = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showPermissionRationale.value = true
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = com.ridervoice.security.SecurePreferences(newBase)
        val lang = prefs.getAppLanguage()
        val localized = com.ridervoice.utils.LocaleHelper.getLocalizedContext(newBase, lang)
        super.attachBaseContext(localized)
    }

    override fun onResume() {
        super.onResume()
        com.ridervoice.services.VoiceForegroundService.refreshTypes(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(PermissionManager.requiredPermissions)

        val initialDarkTheme = securePreferences.getBoolean("dark_theme_enabled", false)
        com.ridervoice.ui.theme.ThemeState.set(initialDarkTheme)
        com.ridervoice.ui.theme.RideErgonomicsSettings.setHapticFeedbackEnabled(
            securePreferences.isHapticFeedbackEnabled()
        )
        com.ridervoice.ui.theme.RideErgonomicsSettings.setKeepScreenOnDuringRide(
            securePreferences.isKeepScreenOnDuringRide()
        )
        val initialBarStyle = if (initialDarkTheme) {
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        }
        enableEdgeToEdge(statusBarStyle = initialBarStyle, navigationBarStyle = initialBarStyle)

        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
            lifecycleScope.launch {
                com.ridervoice.services.FcmTokenManager.registerCurrentToken(apiService)
            }
        }

        val deepLinkToken = com.ridervoice.navigation.DeepLinkHolder.parseDeepLinkToken(intent?.data)
        if (!deepLinkToken.isNullOrBlank()) {
            com.ridervoice.navigation.DeepLinkHolder.set(deepLinkToken)
        }
        val initialRoute = if (!deepLinkToken.isNullOrBlank()) {
            com.ridervoice.navigation.Routes.joinViaTokenPath(deepLinkToken)
        } else {
            intent?.getStringExtra("NAV_ROUTE")
        }

        setContent {
            val appLang by securePreferences.appLanguage.collectAsStateWithLifecycle()
            val localizedContext = remember(appLang) {
                com.ridervoice.utils.LocaleHelper.getLocalizedContext(this@MainActivity, appLang)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
                androidx.compose.ui.platform.LocalConfiguration provides localizedContext.resources.configuration
            ) {
                com.ridervoice.ui.theme.RiderVoiceTheme {
                val showUpdateSuccessDialog = remember { mutableStateOf(false) }
                val updatedVersionName = remember { mutableStateOf("") }
                val crashReportText = remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    try {
                        val crashFile = java.io.File(filesDir, App.CRASH_FILE_NAME)
                        if (crashFile.exists()) {
                            val content = crashFile.readText().trim()
                            if (content.isNotBlank()) {
                                crashReportText.value = content
                            }
                        }
                    } catch (_: Exception) {}

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

                crashReportText.value?.let { report ->
                    AlertDialog(
                        onDismissRequest = {
                            try { java.io.File(filesDir, App.CRASH_FILE_NAME).delete() } catch (_: Exception) {}
                            crashReportText.value = null
                        },
                        containerColor = PaperRaised,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Brick,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.crash_dialog_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = Brick,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            ) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.crash_dialog_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = report,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        color = Ink
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText(getString(R.string.crash_clip_label), report)
                                    clipboard?.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.crash_copied_toast), android.widget.Toast.LENGTH_SHORT).show()
                                    try { java.io.File(filesDir, App.CRASH_FILE_NAME).delete() } catch (_: Exception) {}
                                    crashReportText.value = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Brick)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.crash_copy_button), color = PaperOnBrick, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    try { java.io.File(filesDir, App.CRASH_FILE_NAME).delete() } catch (_: Exception) {}
                                    crashReportText.value = null
                                }
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.action_dismiss), color = Ink)
                            }
                        }
                    )
                }

                if (showUpdateSuccessDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showUpdateSuccessDialog.value = false },
                        containerColor = PaperRaised,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Moss,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.update_complete_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            val cleanUpdatedVer = updatedVersionName.value.removePrefix("v").removePrefix("V")
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.update_complete_body, cleanUpdatedVer),
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { showUpdateSuccessDialog.value = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Pop)
                            ) {
                                Text(
                                    androidx.compose.ui.res.stringResource(R.string.action_continue),
                                    color = PaperOnPop,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }

                val isUpdateRequired = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    appEvents.updateRequired.collect {
                        isUpdateRequired.value = true
                    }
                }
                if (isUpdateRequired.value) {
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = PaperRaised,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Pop,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.update_required_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.update_required_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BuzyU/Rider_APP/releases/latest"))
                                    startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Pop)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.update_required_button), color = PaperOnPop, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                if (showPermissionRationale.value) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale.value = false },
                        title = { Text(androidx.compose.ui.res.stringResource(R.string.perm_rationale_title)) },
                        text = {
                            Text(androidx.compose.ui.res.stringResource(R.string.perm_rationale_body))
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPermissionRationale.value = false
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }) {
                                Text(androidx.compose.ui.res.stringResource(R.string.perm_open_settings))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionRationale.value = false }) {
                                Text(androidx.compose.ui.res.stringResource(R.string.perm_continue_anyway))
                            }
                        }
                    )
                }
            }
        }
    }
}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val token = com.ridervoice.navigation.DeepLinkHolder.parseDeepLinkToken(intent.data)
        if (!token.isNullOrBlank()) {
            com.ridervoice.navigation.DeepLinkHolder.set(token)
        }
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent?): Boolean {
        return try {
            super.dispatchGenericMotionEvent(ev)
        } catch (e: IllegalStateException) {
            true
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalStateException) {
            true
        }
    }
}

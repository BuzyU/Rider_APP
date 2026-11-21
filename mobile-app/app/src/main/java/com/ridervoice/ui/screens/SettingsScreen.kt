package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.components.UpdateDialog
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.SettingsViewModel
import com.ridervoice.ui.viewmodels.UpdateViewModel

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    meshSettingsViewModel: com.ridervoice.mesh.ui.MeshSettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onNavigateToAccount: () -> Unit = {},
    onDeviceSetupClick: () -> Unit = {},
    onSignOutSuccess: () -> Unit = {},
    onNavigateToOfflineMeshLobby: () -> Unit = {}
) {
    val settingsState by settingsViewModel.settingsState.collectAsState()

    var showOptionsDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .statusBarsPadding()
        ) {
            // Top Header matching Dashboard, History & My Squad
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    color = Ink,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = onNavigateToAccount,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PaperRaised)
                        .border(1.dp, InkHairline, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Account",
                        tint = Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SettingsGroupCard(title = "PROFILE") {
                        SettingsRowValue(
                            label = "My Profile",
                            value = "View account",
                            icon = Icons.Default.AccountCircle,
                            onClick = onNavigateToAccount
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = "DISPLAY & LANGUAGE") {
                        SettingsRowToggle(
                            label = "Dark Mode",
                            icon = Icons.Default.DarkMode,
                            checked = settingsState.darkTheme,
                            onCheckedChange = { settingsViewModel.toggleDarkTheme() }
                        )
                        SettingsRowDivider()
                        SettingsRowValue(
                            label = "Language",
                            value = when (settingsState.appLanguage) {
                                "hi" -> "हिन्दी"
                                "mr" -> "मराठी"
                                "en" -> "English"
                                else -> "System default"
                            },
                            icon = Icons.Default.Language,
                            onClick = {
                                showOptionsDialog = "appLanguage" to listOf("System default", "English", "हिन्दी", "मराठी")
                            }
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = "AUDIO & VOICE") {
                        SettingsRowAction(
                            label = stringResource(R.string.settings_headset_setup_title),
                            subtitle = stringResource(R.string.settings_headset_setup_subtitle),
                            icon = Icons.Default.HeadsetMic,
                            onClick = onDeviceSetupClick
                        )
                        SettingsRowDivider()
                        SettingsRowValue(
                            label = "VOX Sensitivity",
                            value = settingsState.voxSensitivity,
                            icon = Icons.Default.RecordVoiceOver,
                            onClick = { showOptionsDialog = "voxSensitivity" to listOf("Low", "Medium", "High") }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = "Noise Cancellation",
                            icon = Icons.Default.Hearing,
                            checked = settingsState.noiseCancellation,
                            onCheckedChange = { settingsViewModel.toggleNoiseCancellation() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = "Hardware PTT (Helmet Button)",
                            icon = Icons.Default.Headset,
                            checked = settingsState.hardwarePtt,
                            onCheckedChange = { settingsViewModel.toggleHardwarePtt() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = "Hybrid VOX + PTT Mode",
                            icon = Icons.Default.HeadsetMic,
                            checked = settingsState.hybridVox,
                            onCheckedChange = { settingsViewModel.toggleHybridVox() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = "Voice activation (VOX)",
                            subtitle = if (settingsState.openMic) "Voice gate active (hands-free)" else "Push-to-Talk only",
                            icon = Icons.Default.Podcasts,
                            checked = settingsState.openMic,
                            onCheckedChange = { settingsViewModel.toggleOpenMic() }
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = stringResource(R.string.mesh_settings_section_header).uppercase()) {
                        SettingsRowInfo(
                            label = stringResource(R.string.mesh_settings_title),
                            subtitle = "Configured per ride in the waiting room",
                            icon = Icons.Default.WifiTethering
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = "DIAGNOSTICS") {
                        SettingsRowToggle(
                            label = "Share diagnostics when host enables it",
                            icon = Icons.Default.NetworkCheck,
                            checked = settingsState.shareDiagnosticsWhenHostEnabled,
                            subtitle = if (settingsState.shareDiagnosticsWhenHostEnabled) {
                                "Diagnostics shared only during diagnostics-enabled rides"
                            } else {
                                "Opted out: no diagnostics will be collected or shared"
                            },
                            onCheckedChange = { settingsViewModel.toggleShareDiagnosticsWhenHostEnabled() }
                        )
                    }
                }

                item {
                    val ctx = LocalContext.current
                    val emergencyInfo = remember(settingsState.emergencyCountry) {
                        com.ridervoice.utils.EmergencyNumbers.resolve(ctx, settingsState.emergencyCountry)
                    }
                    val currentDisplay = when (settingsState.emergencyCountry) {
                        "IN" -> "India (112)"
                        "US" -> "United States (911)"
                        "GB" -> "United Kingdom (999)"
                        "AU" -> "Australia (000)"
                        "JP" -> "Japan (119)"
                        "CA" -> "Canada (911)"
                        "OTHER" -> "Other / Global (112)"
                        null, "" -> "Auto (${emergencyInfo.countryIso ?: "112"})"
                        else -> "${settingsState.emergencyCountry} (${emergencyInfo.number})"
                    }
                    SettingsGroupCard(title = "SAFETY & UPDATES") {
                        SettingsRowValue(
                            label = "Emergency Country",
                            value = currentDisplay,
                            icon = Icons.Default.LocalHospital,
                            onClick = {
                                showOptionsDialog = "emergencyCountry" to listOf(
                                    "Auto (Device Region)",
                                    "India (112)",
                                    "United States (911)",
                                    "United Kingdom (999)",
                                    "Australia (000)",
                                    "Japan (119)",
                                    "Canada (911)",
                                    "Other / Global (112)"
                                )
                            }
                        )
                        SettingsRowDivider()
                        SettingsRowValue(
                            label = "App Version",
                            value = "v${updateViewModel.currentVersionName.removePrefix("v").removePrefix("V")}",
                            icon = Icons.Default.SystemUpdate,
                            onClick = { updateViewModel.checkForUpdates() }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showSignOutConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = BrickContainer.copy(alpha = 0.45f),
                            contentColor = Brick
                        ),
                        border = BorderStroke(1.dp, Brick.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            tint = Brick,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }

        val context = LocalContext.current
        val updateState by updateViewModel.uiState.collectAsState()
        UpdateDialog(
            uiState = updateState,
            onConfirmDownload = { updateViewModel.promptDownloadConfirmation(it) },
            onStartDownload = { updateViewModel.startDownload(it) },
            onCancelDownload = { updateViewModel.cancelDownload() },
            onInstallUpdate = { file, info -> updateViewModel.installUpdate(file, info) },
            onOpenSettings = { updateViewModel.openSettings() },
            onRetry = { updateViewModel.checkForUpdates() },
            onDismiss = { updateViewModel.dismiss() },
            onOpenBrowserDownload = { url ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        )

        if (showSignOutConfirmDialog) {
            ConfirmDialog(
                title = "Sign out of RiderVoice?",
                body = "You will disconnect from all background voice services and will need to sign back in.",
                confirmLabel = "Sign Out",
                dismissLabel = "Cancel",
                destructive = true,
                onConfirm = {
                    showSignOutConfirmDialog = false
                    meshSettingsViewModel.clearAllOnSignOut()
                    authViewModel.signOut()
                    onSignOutSuccess()
                },
                onDismiss = { showSignOutConfirmDialog = false }
            )
        }

        showOptionsDialog?.let { (key, options) ->
            val currentValue = when (key) {
                "voxSensitivity" -> settingsState.voxSensitivity
                "appLanguage" -> when (settingsState.appLanguage) {
                    "hi" -> "हिन्दी"
                    "mr" -> "मराठी"
                    "en" -> "English"
                    else -> "System default"
                }
                "emergencyCountry" -> when (settingsState.emergencyCountry) {
                    "IN" -> "India (112)"
                    "US" -> "United States (911)"
                    "GB" -> "United Kingdom (999)"
                    "AU" -> "Australia (000)"
                    "JP" -> "Japan (119)"
                    "CA" -> "Canada (911)"
                    "OTHER" -> "Other / Global (112)"
                    else -> "Auto (Device Region)"
                }
                else -> ""
            }

            AlertDialog(
                onDismissRequest = { showOptionsDialog = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = PaperRaised,
                title = {
                    Text(
                        text = when (key) {
                            "appLanguage" -> "Select Language"
                            "emergencyCountry" -> "Emergency Country"
                            else -> "VOX Sensitivity"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { option ->
                            val isSelected = option == currentValue
                            Surface(
                                color = if (isSelected) PopContainer else PaperSunken.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Pop.copy(alpha = 0.35f) else Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (key == "appLanguage") {
                                            val code = when (option) {
                                                "हिन्दी" -> "hi"
                                                "मराठी" -> "mr"
                                                "English" -> "en"
                                                else -> "system"
                                            }
                                            settingsViewModel.setAppLanguage(code)
                                        } else {
                                            settingsViewModel.updateSettingValue(key, option)
                                        }
                                        showOptionsDialog = null
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = option,
                                        color = if (isSelected) Pop else Ink,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Pop,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOptionsDialog = null }) {
                        Text("Close", color = Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        val showDisclosure by settingsViewModel.showDiagnosticDisclosure.collectAsState()
        if (showDisclosure) {
            AlertDialog(
                onDismissRequest = { settingsViewModel.dismissDiagnosticDisclosure() },
                shape = RoundedCornerShape(24.dp),
                containerColor = PaperRaised,
                title = {
                    Text(
                        text = "Network Diagnostics",
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "What is collected:\n" +
                            "• Network stats: RTT latency, packet loss %, jitter, bitrates.\n" +
                            "• Connection events: reconnects, health changes, mute state.\n" +
                            "• Rider handles: handles in active ride (no contacts or tokens).\n" +
                            "• Device health: battery level, thermal state, network type.\n" +
                            "• Location: optional (default OFF).\n\n" +
                            "Where it goes:\n" +
                            "Uploaded over HTTPS to the developer diagnostics backend and emailed as an encrypted archive to aid debugging call drops.\n\n" +
                            "Retention: All runs and batches are automatically purged after 30 days.",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { settingsViewModel.confirmDiagnosticDisclosure() },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Agree & Enable", color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.dismissDiagnosticDisclosure() }) {
                        Text("Cancel", color = Ink)
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = InkSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.9.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        Surface(
            color = PaperRaised,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, InkHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRowDivider() {
    Divider(
        color = InkHairline,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 66.dp, end = 16.dp)
    )
}

@Composable
private fun SettingsRowValue(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = Pop,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsRowAction(
    label: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsRowInfo(
    label: String,
    subtitle: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SettingsRowToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = if (enabled) onCheckedChange else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PaperOnPop,
                checkedTrackColor = Pop,
                uncheckedThumbColor = InkSecondary,
                uncheckedTrackColor = PaperSunken
            )
        )
    }
}

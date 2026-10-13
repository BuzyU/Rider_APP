package com.ridervoice.ui.screens

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.SettingsViewModel
import com.ridervoice.ui.viewmodels.UpdateViewModel
import com.ridervoice.ui.components.UpdateDialog

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onNavigateToAccount: () -> Unit = {},
    onSignOutSuccess: () -> Unit = {}
) {
    val settingsState by settingsViewModel.settingsState.collectAsState()

    var showOptionsDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GraphiteBase)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "APP SETTINGS",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonOrange,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "SETTINGS",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    SettingsSectionHeader("PROFILE")
                    SettingsItemValue(
                        label = "My Profile",
                        value = "View Account",
                        icon = Icons.Default.AccountCircle,
                        onClick = onNavigateToAccount
                    )
                }

                item {
                    SettingsSectionHeader("DISPLAY")
                    SettingsItemToggle(
                        label = "Dark Mode",
                        icon = Icons.Default.DarkMode,
                        checked = settingsState.darkTheme,
                        onCheckedChange = { settingsViewModel.toggleDarkTheme() }
                    )
                }

                item {
                    SettingsSectionHeader("AUDIO")
                    SettingsItemValue(
                        label = "VOX Sensitivity",
                        value = settingsState.voxSensitivity,
                        icon = Icons.Default.RecordVoiceOver,
                        onClick = { showOptionsDialog = "voxSensitivity" to listOf("Low", "Medium", "High") }
                    )
                    SettingsItemToggle(
                        label = "Noise Cancellation",
                        icon = Icons.Default.Hearing,
                        checked = settingsState.noiseCancellation,
                        onCheckedChange = { settingsViewModel.toggleNoiseCancellation() }
                    )
                    SettingsItemToggle(
                        label = "Hardware PTT (Helmet Button)",
                        icon = Icons.Default.Headset,
                        checked = settingsState.hardwarePtt,
                        onCheckedChange = { settingsViewModel.toggleHardwarePtt() }
                    )
                    SettingsItemToggle(
                        label = "Hybrid VOX + PTT Mode",
                        icon = Icons.Default.HeadsetMic,
                        checked = settingsState.hybridVox,
                        onCheckedChange = { settingsViewModel.toggleHybridVox() }
                    )
                }

                item {
                    SettingsSectionHeader("RIDE HUD")
                    SettingsItemToggle(
                        label = "High-Contrast Mode",
                        icon = Icons.Default.Dashboard,
                        checked = settingsState.autoHudMode,
                        onCheckedChange = { settingsViewModel.toggleAutoHud() }
                    )
                    SettingsItemToggle(
                        label = "Glove Mode",
                        icon = Icons.Default.TouchApp,
                        checked = settingsState.gloveMode,
                        onCheckedChange = { settingsViewModel.toggleGloveMode() }
                    )
                }

                item {
                    SettingsSectionHeader("COMMUNICATION")
                    SettingsItemToggle(
                        label = "Open Mic (always transmit)",
                        icon = Icons.Default.Podcasts,
                        checked = settingsState.openMic,
                        onCheckedChange = { settingsViewModel.toggleOpenMic() }
                    )
                }

                item {
                    SettingsSectionHeader("UPDATES")
                    SettingsItemValue(
                        label = "App Version",
                        value = "v${updateViewModel.currentVersionName}",
                        icon = Icons.Default.Info,
                        onClick = { updateViewModel.checkForUpdates() }
                    )
                    SettingsItemValue(
                        label = "Check for Updates",
                        value = "Check Now",
                        icon = Icons.Default.SystemUpdate,
                        onClick = { updateViewModel.checkForUpdates() }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                    TacticalButton(
                        text = "SIGN OUT",
                        onClick = {
                            showSignOutConfirmDialog = true
                        },
                        isOutlined = true,
                        color = DarkSlate,
                        textColor = AlertRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }

        val updateState by updateViewModel.uiState.collectAsState()
        UpdateDialog(
            uiState = updateState,
            onConfirmDownload = { updateViewModel.promptDownloadConfirmation(it) },
            onStartDownload = { updateViewModel.startDownload(it) },
            onCancelDownload = { updateViewModel.cancelDownload() },
            onInstallUpdate = { file, info -> updateViewModel.installUpdate(file, info) },
            onOpenSettings = { updateViewModel.openSettings() },
            onRetry = { updateViewModel.checkForUpdates() },
            onDismiss = { updateViewModel.dismiss() }
        )

        if (showSignOutConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutConfirmDialog = false },
                title = {
                    Text(
                        text = "SIGN OUT OF RIDERVOICE?",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = "You will disconnect from all background voice services and will need to log back in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSignOutConfirmDialog = false
                            authViewModel.signOut()
                            onSignOutSuccess()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                    ) {
                        Text("SIGN OUT", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSignOutConfirmDialog = false }) {
                        Text("CANCEL", color = TextPrimary)
                    }
                },
                containerColor = DarkSlate
            )
        }

        showOptionsDialog?.let { (key, options) ->
            val currentValue = when (key) {
                "voxSensitivity"  -> settingsState.voxSensitivity
                else              -> ""
            }

            AlertDialog(
                onDismissRequest = { showOptionsDialog = null },
                title = {
                    Text(
                        text = "SELECT OPTION",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                },
                containerColor = DarkSlate,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        options.forEach { option ->
                            val isSelected = option == currentValue
                            Surface(
                                color = if (isSelected) Gunmetal else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsViewModel.updateSettingValue(key, option)
                                        showOptionsDialog = null
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = option,
                                        color = if (isSelected) NeonOrange else TextPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 15.sp
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = TechGreen,
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
                        Text("CLOSE", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = "── $title ──",
        color = NeonOrange,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.5.sp,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 10.dp)
    )
}

@Composable
fun SettingsItemValue(
    label: String,
    value: String,
    icon: ImageVector = Icons.Default.VolumeUp,
    onClick: () -> Unit = {}
) {
    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Gunmetal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = label,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    color = NeonOrange,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun SettingsItemToggle(
    label: String,
    icon: ImageVector = Icons.Default.Tune,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val isDark = ThemeState.isDarkTheme

    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Gunmetal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = label,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (isDark) Color.Black else Color.White,
                    checkedTrackColor = TechGreen,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = Gunmetal
                )
            )
        }
    }
}

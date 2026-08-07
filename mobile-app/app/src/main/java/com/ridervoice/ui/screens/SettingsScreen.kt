package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.SettingsViewModel
import com.ridervoice.ui.components.TacticalButton

@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onSignOutSuccess: () -> Unit
) {
    val settingsState by settingsViewModel.settingsState.collectAsState()

    var showOptionsDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GraphiteBase)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    text = "SETTINGS",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                item {
                    SettingsSectionHeader("APPEARANCE")
                    SettingsItemToggle(
                        label = "Dark Mode",
                        checked = settingsState.darkTheme,
                        onCheckedChange = { settingsViewModel.toggleDarkTheme() }
                    )
                }

                item {
                    SettingsSectionHeader("AUDIO SETTINGS")
                    SettingsItemValue(
                        label = "Mic Sensitivity",
                        value = settingsState.micSensitivity,
                        onClick = { showOptionsDialog = "micSensitivity" to listOf("Low", "Medium", "High") }
                    )
                    SettingsItemValue(
                        label = "VOX Sensitivity",
                        value = settingsState.voxSensitivity,
                        onClick = { showOptionsDialog = "voxSensitivity" to listOf("Low", "Medium", "High") }
                    )
                    SettingsItemToggle(
                        label = "Noise Cancellation",
                        checked = settingsState.noiseCancellation,
                        onCheckedChange = { settingsViewModel.toggleNoiseCancellation() }
                    )
                    SettingsItemValue(
                        label = "Audio Output",
                        value = settingsState.audioOutput,
                        onClick = { showOptionsDialog = "audioOutput" to listOf("Auto", "Bluetooth SCO", "Wired", "Earpiece") }
                    )
                }
                
                item {
                    SettingsSectionHeader("RIDING SETTINGS")
                    SettingsItemToggle(
                        label = "Auto HUD Mode",
                        checked = settingsState.autoHudMode,
                        onCheckedChange = { settingsViewModel.toggleAutoHud() }
                    )
                    SettingsItemValue(
                        label = "Speed for HUD",
                        value = settingsState.speedForHud,
                        onClick = { showOptionsDialog = "speedForHud" to listOf("10 km/h", "15 km/h", "20 km/h", "Disabled") }
                    )
                    SettingsItemToggle(
                        label = "Glove Mode",
                        checked = settingsState.gloveMode,
                        onCheckedChange = { settingsViewModel.toggleGloveMode() }
                    )
                }

                item {
                    SettingsSectionHeader("COMMUNICATION")
                    SettingsItemToggle(
                        label = "Open Mic",
                        checked = settingsState.openMic,
                        onCheckedChange = { settingsViewModel.toggleOpenMic() }
                    )
                    SettingsItemValue(
                        label = "Reconnect",
                        value = settingsState.reconnectMode,
                        onClick = { showOptionsDialog = "reconnectMode" to listOf("Auto", "Manual") }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    TacticalButton(
                        text = "Sign Out",
                        onClick = { 
                            authViewModel.signOut() 
                            onSignOutSuccess()
                        },
                        isOutlined = true,
                        color = Color(0xFF1D232B),
                        textColor = NeonOrange,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
        // Bottom Navigation Bar Removed        // Options Dialog
        showOptionsDialog?.let { (key, options) ->
            AlertDialog(
                onDismissRequest = { showOptionsDialog = null },
                title = { Text(text = "Select Option", color = TextPrimary) },
                containerColor = Gunmetal,
                text = {
                    Column {
                        options.forEach { option ->
                            Text(
                                text = option,
                                color = ElectricCyan,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsViewModel.updateSettingValue(key, option)
                                        showOptionsDialog = null
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOptionsDialog = null }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 24.dp, top = 32.dp, bottom = 16.dp)
    )
}

@Composable
fun SettingsItemValue(label: String, value: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = ElectricCyan, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SettingsItemToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = SuccessGreen,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Gunmetal
            )
        )
    }
}

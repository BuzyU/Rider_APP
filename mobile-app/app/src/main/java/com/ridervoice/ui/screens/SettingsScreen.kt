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
import com.ridervoice.ui.components.CapChromeFontScale
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
            CapChromeFontScale(maxScale = 1.3f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nav_settings),
                        color = Ink,
                        style = MaterialTheme.typography.displayLarge,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SettingsGroupCard(title = stringResource(R.string.settings_section_profile)) {
                        SettingsRowValue(
                            label = stringResource(R.string.profile_title),
                            value = stringResource(R.string.settings_view_account),
                            icon = Icons.Default.AccountCircle,
                            onClick = onNavigateToAccount
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = stringResource(R.string.settings_section_display)) {
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_dark_mode),
                            icon = Icons.Default.DarkMode,
                            checked = settingsState.darkTheme,
                            onCheckedChange = { settingsViewModel.toggleDarkTheme() }
                        )
                        SettingsRowDivider()
                        SettingsRowValue(
                            label = stringResource(R.string.settings_language),
                            value = when (settingsState.appLanguage) {
                                "hi" -> stringResource(R.string.language_hi)
                                "mr" -> stringResource(R.string.language_mr)
                                "en" -> stringResource(R.string.language_en)
                                else -> stringResource(R.string.language_system)
                            },
                            icon = Icons.Default.Language,
                            onClick = {
                                showOptionsDialog = "appLanguage" to listOf("system", "en", "hi", "mr")
                            }
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = stringResource(R.string.settings_section_audio_voice)) {
                        SettingsRowAction(
                            label = stringResource(R.string.settings_headset_setup_title),
                            subtitle = stringResource(R.string.settings_headset_setup_subtitle),
                            icon = Icons.Default.HeadsetMic,
                            onClick = onDeviceSetupClick
                        )
                        SettingsRowDivider()
                        val voxDisplay = when (settingsState.voxSensitivity) {
                            "Low" -> stringResource(R.string.settings_vox_low)
                            "High" -> stringResource(R.string.settings_vox_high)
                            else -> stringResource(R.string.settings_vox_medium)
                        }
                        SettingsRowValue(
                            label = stringResource(R.string.settings_vox_title),
                            value = voxDisplay,
                            icon = Icons.Default.RecordVoiceOver,
                            onClick = { showOptionsDialog = "voxSensitivity" to listOf("Low", "Medium", "High") }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_noise_cancellation),
                            icon = Icons.Default.Hearing,
                            checked = settingsState.noiseCancellation,
                            onCheckedChange = { settingsViewModel.toggleNoiseCancellation() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_hardware_ptt),
                            icon = Icons.Default.Headset,
                            checked = settingsState.hardwarePtt,
                            onCheckedChange = { settingsViewModel.toggleHardwarePtt() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_hybrid_vox),
                            icon = Icons.Default.HeadsetMic,
                            checked = settingsState.hybridVox,
                            onCheckedChange = { settingsViewModel.toggleHybridVox() }
                        )
                        SettingsRowDivider()
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_voice_activation),
                            subtitle = if (settingsState.openMic) stringResource(R.string.settings_open_mic_on) else stringResource(R.string.settings_open_mic_off),
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
                            subtitle = stringResource(R.string.settings_mesh_lobby_note),
                            icon = Icons.Default.WifiTethering
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = stringResource(R.string.settings_section_diagnostics)) {
                        SettingsRowToggle(
                            label = stringResource(R.string.settings_share_diag_label),
                            icon = Icons.Default.NetworkCheck,
                            checked = settingsState.shareDiagnosticsWhenHostEnabled,
                            subtitle = if (settingsState.shareDiagnosticsWhenHostEnabled) {
                                stringResource(R.string.settings_share_diag_on)
                            } else {
                                stringResource(R.string.settings_share_diag_off)
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
                        "IN" -> stringResource(R.string.settings_country_in)
                        "US" -> stringResource(R.string.settings_country_us)
                        "GB" -> stringResource(R.string.settings_country_gb)
                        "AU" -> stringResource(R.string.settings_country_au)
                        "JP" -> stringResource(R.string.settings_country_jp)
                        "CA" -> stringResource(R.string.settings_country_ca)
                        "OTHER" -> stringResource(R.string.settings_country_other)
                        null, "" -> stringResource(R.string.settings_country_auto_fmt, emergencyInfo.countryIso ?: "112")
                        else -> "${settingsState.emergencyCountry} (${emergencyInfo.number})"
                    }
                    SettingsGroupCard(title = stringResource(R.string.settings_section_safety_updates)) {
                        SettingsRowValue(
                            label = stringResource(R.string.settings_emergency_country),
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
                            label = stringResource(R.string.settings_app_version),
                            value = "v${updateViewModel.currentVersionName.removePrefix("v").removePrefix("V")}",
                            icon = Icons.Default.SystemUpdate,
                            onClick = { updateViewModel.checkForUpdates(forceRefresh = true) }
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
                        Text(stringResource(R.string.action_sign_out), fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            onRetry = { updateViewModel.checkForUpdates(forceRefresh = true) },
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
                title = stringResource(R.string.dialog_sign_out_title),
                body = stringResource(R.string.dialog_sign_out_settings_body),
                confirmLabel = stringResource(R.string.action_sign_out),
                dismissLabel = stringResource(R.string.action_cancel),
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
                "appLanguage" -> settingsState.appLanguage
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
                            "appLanguage" -> stringResource(R.string.settings_select_language)
                            "emergencyCountry" -> stringResource(R.string.settings_emergency_country)
                            else -> stringResource(R.string.settings_vox_title)
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
                            val optionLabel = when (key) {
                                "appLanguage" -> when (option) {
                                    "hi" -> stringResource(R.string.language_hi)
                                    "mr" -> stringResource(R.string.language_mr)
                                    "en" -> stringResource(R.string.language_en)
                                    else -> stringResource(R.string.language_system)
                                }
                                "voxSensitivity" -> when (option) {
                                    "Low" -> stringResource(R.string.settings_vox_low)
                                    "High" -> stringResource(R.string.settings_vox_high)
                                    else -> stringResource(R.string.settings_vox_medium)
                                }
                                "emergencyCountry" -> when (option) {
                                    "India (112)" -> stringResource(R.string.settings_country_in)
                                    "United States (911)" -> stringResource(R.string.settings_country_us)
                                    "United Kingdom (999)" -> stringResource(R.string.settings_country_gb)
                                    "Australia (000)" -> stringResource(R.string.settings_country_au)
                                    "Japan (119)" -> stringResource(R.string.settings_country_jp)
                                    "Canada (911)" -> stringResource(R.string.settings_country_ca)
                                    "Other / Global (112)" -> stringResource(R.string.settings_country_other)
                                    else -> stringResource(R.string.settings_country_auto_region)
                                }
                                else -> option
                            }
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
                                            settingsViewModel.setAppLanguage(option)
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
                                        text = optionLabel,
                                        color = if (isSelected) Pop else Ink,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.cd_selected),
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
                        Text(stringResource(R.string.action_close), color = Ink, fontWeight = FontWeight.SemiBold)
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
                        text = stringResource(R.string.settings_diag_dialog_title),
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.settings_diag_dialog_body),
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
                        Text(stringResource(R.string.settings_diag_agree_enable), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.dismissDiagnosticDisclosure() }) {
                        Text(stringResource(R.string.action_cancel), color = Ink)
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

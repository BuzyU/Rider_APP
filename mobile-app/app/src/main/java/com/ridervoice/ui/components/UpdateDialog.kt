package com.ridervoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.theme.*
import com.ridervoice.update.AppReleaseInfo
import com.ridervoice.update.UpdateUiState

@Composable
fun UpdateDialog(
    uiState: UpdateUiState,
    onConfirmDownload: (AppReleaseInfo) -> Unit,
    onStartDownload: (AppReleaseInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: (java.io.File, AppReleaseInfo) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenBrowserDownload: ((String) -> Unit)? = null
) {
    when (uiState) {
        is UpdateUiState.Idle -> {

        }

        is UpdateUiState.Checking -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = stringResource(R.string.update_checking_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = NeonOrange,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.update_checking_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateUiState.UpToDate -> {
            val cleanCurrent = uiState.currentVersion.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
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
                        text = stringResource(R.string.update_uptodate_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_uptodate_desc, cleanCurrent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        val notes = uiState.releaseNotes
                        if (!notes.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.update_whats_new_version, cleanCurrent),
                                color = NeonOrange,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.sp
                            )
                            Surface(
                                color = GraphiteBase,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = notes,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Gunmetal)
                    ) {
                        Text(stringResource(R.string.action_ok), color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        is UpdateUiState.UpdateAvailable -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.update_badge_app),
                            style = MaterialTheme.typography.labelSmall,
                            color = Pop,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = stringResource(R.string.update_available_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Surface(
                            color = Gunmetal,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.update_current_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    Text("v${uiState.currentVersion.removePrefix("v").removePrefix("V")}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(R.string.update_newest_label, uiState.releaseInfo.formattedSize), color = TechGreen, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    Text("v$cleanVer", color = TechGreen, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.update_whats_new),
                            color = NeonOrange,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp
                        )

                        Surface(
                            color = GraphiteBase,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = uiState.releaseInfo.releaseNotes,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                lineHeight = 18.sp
                            )
                        }

                        Text(
                            text = stringResource(R.string.update_safety_note),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onConfirmDownload(uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        Text(stringResource(R.string.update_action_download_now), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_action_later), color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.ConfirmDownload -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = stringResource(R.string.update_confirm_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.update_confirm_body, cleanVer, uiState.releaseInfo.formattedSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.update_confirm_storage_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onStartDownload(uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        Text(stringResource(R.string.action_download), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.Downloading -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = {},
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.update_downloading_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = stringResource(R.string.update_downloading_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.app_name)} v$cleanVer",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )

                        LinearProgressIndicator(
                            progress = (uiState.progressPercent / 100f).coerceIn(0f, 1f),
                            color = NeonOrange,
                            trackColor = Gunmetal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${uiState.progressPercent}%",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = uiState.speedText,
                                color = TechGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        val dlMb = "%.1f MB".format(uiState.downloadedBytes.toDouble() / (1024.0 * 1024.0))
                        val totalMb = "%.1f MB".format(uiState.totalBytes.toDouble() / (1024.0 * 1024.0))
                        Text(
                            text = stringResource(R.string.update_downloaded_progress, dlMb, totalMb),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.update_cancel_download), color = AlertRed)
                    }
                }
            )
        }

        is UpdateUiState.Verifying -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = stringResource(R.string.update_verifying_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.update_verifying_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateUiState.ReadyToInstall -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = null,
                        tint = TechGreen,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.update_download_complete_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.update_ready_install_line, cleanVer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.update_install_prompt_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onInstallUpdate(uiState.apkFile, uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        Text(stringResource(R.string.update_action_install_now), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_action_later), color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.RequestUnknownSourcesPermission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = NeonOrange,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.update_permission_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.update_permission_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.update_permission_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onOpenSettings() },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        Text(stringResource(R.string.action_open_settings), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.Installing -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = stringResource(R.string.update_installer_launched_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.update_installer_launched_body, cleanVer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.update_installer_launched_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onInstallUpdate(uiState.apkFile, uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        Text(stringResource(R.string.update_retry_installer), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close), color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.update_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        if (uiState.downloadUrl != null && onOpenBrowserDownload != null) {
                            Text(
                                text = stringResource(R.string.update_error_browser_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TechGreen
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.downloadUrl != null && onOpenBrowserDownload != null) {
                            OutlinedButton(
                                onClick = { onOpenBrowserDownload(uiState.downloadUrl) },
                                border = androidx.compose.foundation.BorderStroke(1.dp, NeonOrange),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonOrange)
                            ) {
                                Text(stringResource(R.string.update_action_browser), fontWeight = FontWeight.Bold)
                            }
                        }
                        if (uiState.canRetry) {
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = Pop)
                            ) {
                                Text(stringResource(R.string.action_retry), color = PaperOnPop, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = Gunmetal)
                            ) {
                                Text(stringResource(R.string.action_close), color = TextPrimary)
                            }
                        }
                    }
                },
                dismissButton = {
                    if (uiState.canRetry) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel), color = TextPrimary)
                        }
                    }
                }
            )
        }
    }
}

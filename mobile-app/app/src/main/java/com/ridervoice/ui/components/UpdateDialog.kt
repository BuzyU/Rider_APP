package com.ridervoice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridervoice.R
import com.ridervoice.ui.theme.*
import com.ridervoice.update.AppReleaseInfo
import com.ridervoice.update.ReleaseNoteCategory
import com.ridervoice.update.ReleaseNoteSection
import com.ridervoice.update.ReleaseNotesParser
import com.ridervoice.update.UpdateUiState
import java.util.Locale
import com.ridervoice.ui.theme.RvDateFormat
import com.ridervoice.ui.theme.rememberAppLocale

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
        is UpdateUiState.Idle -> Unit

        is UpdateUiState.Checking -> {
            UpdateDialogShell(
                title = stringResource(R.string.update_checking_title),
                subtitle = null,
                icon = Icons.Default.SystemUpdate,
                iconBg = PopContainer,
                iconTint = Pop,
                dismissible = false,
                onDismiss = {}
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Pop,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.update_checking_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.UpToDate -> {
            val cleanCurrent = uiState.currentVersion.removePrefix("v").removePrefix("V")
            val versionBadge = "v$cleanCurrent"
            UpdateDialogShell(
                title = stringResource(R.string.update_uptodate_title),
                subtitle = null,
                versionBadge = versionBadge,
                icon = Icons.Default.CheckCircle,
                iconBg = MossContainer,
                iconTint = Moss,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CharcoalSurface,
                            contentColor = PaperOnPop
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_ok),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            ) {
                val notes = uiState.releaseNotes
                if (!notes.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 390.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_whats_new_version, cleanCurrent),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        CategorizedReleaseNotesView(rawNotes = notes)
                    }
                }
            }
        }

        is UpdateUiState.UpdateAvailable -> {
            val cleanCurrent = uiState.currentVersion.removePrefix("v").removePrefix("V")
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            val curLabel = "v$cleanCurrent"
            val nextLabel = "v$cleanVer"
            UpdateDialogShell(
                title = stringResource(R.string.update_available_title),
                subtitle = stringResource(R.string.update_badge_app),
                icon = Icons.Default.SystemUpdate,
                iconBg = PopContainer,
                iconTint = Pop,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_action_later),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { onConfirmDownload(uiState.releaseInfo) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Pop,
                                contentColor = PaperOnPop
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(50.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_action_download_now),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Compact Version Transition Strip
                    Surface(
                        color = PaperSunken,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.update_current_label),
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = curLabel,
                                    color = Ink,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.update_newest_label, uiState.releaseInfo.formattedSize),
                                    color = Moss,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = nextLabel,
                                    color = Moss,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.update_whats_new),
                        color = InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )

                    CategorizedReleaseNotesView(rawNotes = uiState.releaseInfo.releaseNotes)

                    Text(
                        text = stringResource(R.string.update_safety_note),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        is UpdateUiState.ConfirmDownload -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            val versionBadge = "v$cleanVer"
            UpdateDialogShell(
                title = stringResource(R.string.update_confirm_title),
                subtitle = null,
                versionBadge = versionBadge,
                icon = Icons.Default.Download,
                iconBg = PopContainer,
                iconTint = Pop,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onStartDownload(uiState.releaseInfo) },
                            colors = ButtonDefaults.buttonColors(containerColor = Pop, contentColor = PaperOnPop),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.25f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.action_download), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.update_confirm_body, cleanVer, uiState.releaseInfo.formattedSize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        text = stringResource(R.string.update_confirm_storage_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.Downloading -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            val versionBadge = "v$cleanVer"
            val hasKnownTotal = uiState.totalBytes > 0L
            val appLocale = rememberAppLocale()
            val dlMb = "${RvDateFormat.formatDecimal(uiState.downloadedBytes.toDouble() / (1024.0 * 1024.0), 1, appLocale)} MB"
            val totalMb = "${RvDateFormat.formatDecimal(uiState.totalBytes.toDouble() / (1024.0 * 1024.0), 1, appLocale)} MB"

            UpdateDialogShell(
                title = stringResource(R.string.update_downloading_title),
                subtitle = stringResource(R.string.update_downloading_badge),
                versionBadge = versionBadge,
                icon = Icons.Default.Downloading,
                iconBg = PopContainer,
                iconTint = Pop,
                dismissible = false,
                onDismiss = {},
                footer = {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Brick),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brick),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.update_cancel_download), fontWeight = FontWeight.Bold)
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (hasKnownTotal) {
                        LinearProgressIndicator(
                            progress = (uiState.progressPercent / 100f).coerceIn(0f, 1f),
                            color = Pop,
                            trackColor = PaperSunken,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(RvRadius.pill))
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Pop,
                            trackColor = PaperSunken,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(RvRadius.pill))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasKnownTotal) "${uiState.progressPercent}%" else dlMb,
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = uiState.speedText,
                            color = Moss,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Text(
                        text = if (hasKnownTotal) {
                            stringResource(R.string.update_downloaded_progress, dlMb, totalMb)
                        } else {
                            stringResource(R.string.update_downloaded_unknown_total, dlMb)
                        },
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        is UpdateUiState.Verifying -> {
            UpdateDialogShell(
                title = stringResource(R.string.update_verifying_title),
                subtitle = null,
                icon = Icons.Default.VerifiedUser,
                iconBg = MossContainer,
                iconTint = Moss,
                dismissible = false,
                onDismiss = {}
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Moss,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.update_verifying_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.ReadyToInstall -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            val versionBadge = "v$cleanVer"
            UpdateDialogShell(
                title = stringResource(R.string.update_download_complete_title),
                subtitle = null,
                versionBadge = versionBadge,
                icon = Icons.Default.DownloadDone,
                iconBg = MossContainer,
                iconTint = Moss,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.update_action_later), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onInstallUpdate(uiState.apkFile, uiState.releaseInfo) },
                            colors = ButtonDefaults.buttonColors(containerColor = Pop, contentColor = PaperOnPop),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.update_action_install_now), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.update_ready_install_line, cleanVer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.update_install_prompt_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.RequestUnknownSourcesPermission -> {
            UpdateDialogShell(
                title = stringResource(R.string.update_permission_title),
                subtitle = null,
                icon = Icons.Default.Security,
                iconBg = PopContainer,
                iconTint = Pop,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onOpenSettings() },
                            colors = ButtonDefaults.buttonColors(containerColor = Pop, contentColor = PaperOnPop),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(50.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_open_settings),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.update_permission_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        text = stringResource(R.string.update_permission_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.Installing -> {
            val cleanVer = uiState.releaseInfo.versionName.removePrefix("v").removePrefix("V")
            val versionBadge = "v$cleanVer"
            UpdateDialogShell(
                title = stringResource(R.string.update_installer_launched_title),
                subtitle = null,
                versionBadge = versionBadge,
                icon = Icons.Default.InstallMobile,
                iconBg = MossContainer,
                iconTint = Moss,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.action_close), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onInstallUpdate(uiState.apkFile, uiState.releaseInfo) },
                            colors = ButtonDefaults.buttonColors(containerColor = Pop, contentColor = PaperOnPop),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                        ) {
                            Text(stringResource(R.string.update_retry_installer), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.update_installer_launched_body, cleanVer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        text = stringResource(R.string.update_installer_launched_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }
        }

        is UpdateUiState.Error -> {
            UpdateDialogShell(
                title = stringResource(R.string.update_failed_title),
                subtitle = null,
                icon = Icons.Default.ErrorOutline,
                iconBg = BrickContainer,
                iconTint = Brick,
                dismissible = true,
                onDismiss = onDismiss,
                footer = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uiState.downloadUrl != null && onOpenBrowserDownload != null) {
                            OutlinedButton(
                                onClick = { onOpenBrowserDownload(uiState.downloadUrl) },
                                border = BorderStroke(1.dp, Pop),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Pop),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(stringResource(R.string.update_action_browser), fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (uiState.canRetry) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, InkHairline),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                ) {
                                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(containerColor = Pop, contentColor = PaperOnPop),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .height(50.dp)
                                ) {
                                    Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.buttonColors(containerColor = CharcoalSurface, contentColor = PaperOnPop),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Text(stringResource(R.string.action_close), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                    if (uiState.downloadUrl != null && onOpenBrowserDownload != null) {
                        Text(
                            text = stringResource(R.string.update_error_browser_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Moss
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-width Roadside Paper modal container with clean 16dp horizontal margins
 * (eliminating Material3 AlertDialog's cramped ~56dp outer borders).
 */
@Composable
private fun UpdateDialogShell(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    versionBadge: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .testTag("update_dialog_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = if (dismissible) 10.dp else 20.dp, top = 18.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(iconBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!versionBadge.isNullOrBlank()) {
                                    Surface(
                                        color = PaperSunken,
                                        shape = RoundedCornerShape(RvRadius.pill),
                                        border = BorderStroke(1.dp, InkHairline)
                                    ) {
                                        Text(
                                            text = versionBadge,
                                            color = Ink,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (dismissible) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(RvTouchTarget.min)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_dismiss),
                                    tint = InkSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Divider(color = InkHairline, thickness = 1.dp)

                    // Body
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        content()
                    }

                    // Footer
                    if (footer != null) {
                        Divider(color = InkHairline, thickness = 1.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            footer()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders categorized release notes in distinct Roadside Paper cards with clean hanging-indent
 * bullets and comfortable vertical spacing — zero raw `#`, `-`, `*`, or backtick clutter.
 */
@Composable
private fun CategorizedReleaseNotesView(rawNotes: String?) {
    val sections = remember(rawNotes) { ReleaseNotesParser.parse(rawNotes) }

    if (sections.isEmpty()) {
        Surface(
            color = Paper,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, InkHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.update_no_release_notes),
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                modifier = Modifier.padding(14.dp)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("update_release_notes_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        sections.forEach { section ->
            ReleaseNoteCategoryCard(section = section)
        }
    }
}

@Composable
private fun ReleaseNoteCategoryCard(section: ReleaseNoteSection) {
    val (labelRes, icon, badgeBg, badgeTint, dotColor) = when (section.category) {
        ReleaseNoteCategory.WHATS_NEW -> Quint(
            R.string.update_cat_whats_new,
            Icons.Default.AutoAwesome,
            PopContainer,
            Pop,
            Pop
        )
        ReleaseNoteCategory.IMPROVEMENTS -> Quint(
            R.string.update_cat_improvements,
            Icons.Default.Tune,
            PaperSunken,
            Ink,
            InkSecondary
        )
        ReleaseNoteCategory.BUG_FIXES -> Quint(
            R.string.update_cat_bug_fixes,
            Icons.Default.Build,
            MossContainer,
            Moss,
            Moss
        )
        ReleaseNoteCategory.RELIABILITY -> Quint(
            R.string.update_cat_reliability,
            Icons.Default.VerifiedUser,
            PaperSunken,
            Ink,
            InkSecondary
        )
        ReleaseNoteCategory.NOTES -> Quint(
            R.string.update_cat_notes,
            Icons.Default.Info,
            PaperSunken,
            InkSecondary,
            InkSecondary
        )
    }

    Surface(
        color = Paper,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("release_category_${section.category.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = badgeTint,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = stringResource(labelRes),
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                val countLabel = section.items.size.toString()
                Surface(
                    color = PaperSunken,
                    shape = RoundedCornerShape(RvRadius.pill)
                ) {
                    Text(
                        text = countLabel,
                        color = InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                section.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = item,
                            color = Ink,
                            style = MaterialTheme.typography.titleSmall,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private data class Quint<A, B, C, D, E>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
    val e: E
)

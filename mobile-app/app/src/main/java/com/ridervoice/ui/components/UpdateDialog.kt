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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onDismiss: () -> Unit
) {
    when (uiState) {
        is UpdateUiState.Idle -> {

        }

        is UpdateUiState.Checking -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = DarkSlate,
                title = {
                    Text(
                        text = "CHECKING FOR UPDATES",
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
                            text = "Querying official release channel...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateUiState.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
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
                        text = "UP TO DATE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "You are currently running the latest version of RiderVoice (v${uiState.currentVersion}). No updates are required.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Gunmetal)
                    ) {
                        Text("OK", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        is UpdateUiState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
                title = {
                    Column {
                        Text(
                            text = "FIRMWARE UPGRADE",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "NEW VERSION AVAILABLE",
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
                                    Text("CURRENT", color = TextSecondary, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    Text("v${uiState.currentVersion}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("NEWEST (${uiState.releaseInfo.formattedSize})", color = TechGreen, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    Text("v${uiState.releaseInfo.versionName}", color = TechGreen, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                }
                            }
                        }

                        Text(
                            text = "WHAT'S NEW",
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
                            text = "The update will be downloaded directly to your device and verified before installation.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onConfirmDownload(uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                    ) {
                        Text("DOWNLOAD NOW", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text("LATER", color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.ConfirmDownload -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
                title = {
                    Text(
                        text = "CONFIRM DOWNLOAD",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Download RiderVoice v${uiState.releaseInfo.versionName} (${uiState.releaseInfo.formattedSize})?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "The APK will be streamed securely over HTTPS and stored in app-internal cache. Storage space has been verified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onStartDownload(uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                    ) {
                        Text("DOWNLOAD", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text("CANCEL", color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = DarkSlate,
                title = {
                    Column {
                        Text(
                            text = "OTA TRANSMISSION",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "DOWNLOADING UPDATE",
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
                            text = "RiderVoice v${uiState.releaseInfo.versionName}",
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
                            text = "Downloaded: $dlMb / $totalMb",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCancelDownload) {
                        Text("CANCEL DOWNLOAD", color = AlertRed)
                    }
                }
            )
        }

        is UpdateUiState.Verifying -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = DarkSlate,
                title = {
                    Text(
                        text = "INTEGRITY AUDIT",
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
                            text = "Verifying package signature and SHA-256 checksum...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateUiState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
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
                        text = "DOWNLOAD COMPLETE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Ready to install RiderVoice v${uiState.releaseInfo.versionName}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tapping 'Install Update' will launch Android's system package installer. Please confirm installation when prompted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onInstallUpdate(uiState.apkFile, uiState.releaseInfo) },
                        colors = ButtonDefaults.buttonColors(containerColor = TechGreen)
                    ) {
                        Text("INSTALL UPDATE", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text("LATER", color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.RequestUnknownSourcesPermission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
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
                        text = "PERMISSION REQUIRED",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "To install firmware updates directly, RiderVoice requires permission to install unknown apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tap 'Open Settings' to enable the toggle, then return to RiderVoice to continue installing the update.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onOpenSettings()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                    ) {
                        Text("OPEN SETTINGS", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) {
                        Text("CANCEL", color = TextPrimary)
                    }
                }
            )
        }

        is UpdateUiState.Installing -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
                title = {
                    Text(
                        text = "LAUNCHING INSTALLER",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "The system installer prompt has been triggered. Please follow the on-screen instructions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("CLOSE", color = ElectricCyan)
                    }
                }
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = DarkSlate,
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
                        text = "UPDATE SERVICE ERROR",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    if (uiState.canRetry) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                        ) {
                            Text("RETRY", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Gunmetal)
                        ) {
                            Text("CLOSE", color = TextPrimary)
                        }
                    }
                },
                dismissButton = {
                    if (uiState.canRetry) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("CANCEL", color = TextPrimary)
                        }
                    }
                }
            )
        }
    }
}

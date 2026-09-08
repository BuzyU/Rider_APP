package com.ridervoice.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyViewModel

@Composable
fun LobbyScreen(
    convoyName: String,
    viewModel: LobbyViewModel = hiltViewModel(),
    onStartRide: () -> Unit,
    onBackClick: () -> Unit,
    onInviteMoreClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val lobbyStatus by viewModel.lobbyStatus.collectAsState()
    val error by viewModel.error.collectAsState()

    var showExitDialog by remember { mutableStateOf(false) }

    // Intercept back navigation
    BackHandler {
        showExitDialog = true
    }

    DisposableEffect(Unit) {
        viewModel.startPolling(convoyName)
        onDispose {
            viewModel.stopPolling()
        }
    }

    // Confirmation dialog before abandoning lobby
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Text(
                    text = "LEAVE CONVOY LOBBY?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Leaving the lobby will stop monitoring rider invitations. You can rejoin or start later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("LEAVE LOBBY", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) {
                    Text("STAY", color = TextPrimary)
                }
            },
            containerColor = DarkSlate
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showExitDialog = true }) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Leave Lobby", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "CONVOY MANIFEST",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = convoyName.uppercase(),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        // Error notification banner if any
        error?.let { err ->
            Surface(
                color = HazardContainer,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = AlertRed, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FAILED TO START: $err",
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (lobbyStatus == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SYNCHRONIZING MANIFEST...",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            val status = lobbyStatus!!
            val pending = status.invites.filter { it.status == "PENDING" }
            val accepted = status.invites.filter { it.status == "ACCEPTED" }
            val declined = status.invites.filter { it.status == "DECLINED" }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section: Mid-lobby quick actions (Share code / Invite more)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Join my RiderVoice convoy '$convoyName' on RiderVoice!")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Convoy Code"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = NeonOrange, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SHARE CODE", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = onInviteMoreClick,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Invite More", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("INVITE SQUAD", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }

                // 1. ACCEPTED / TUNED IN RIDERS
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(TechGreen))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TUNED IN (${accepted.size})",
                            color = TechGreen,
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (accepted.isEmpty()) {
                    item {
                        Surface(
                            color = DarkSlate,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No riders tuned in yet. You can roll out solo or wait for squad.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                } else {
                    items(accepted) { entry ->
                        RiderStatusCard(
                            userName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider",
                            status = "TUNED IN / READY",
                            color = TechGreen,
                            icon = Icons.Default.CheckCircle
                        )
                    }
                }

                // 2. PENDING / STANDBY RIDERS
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(WarningAmber))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STANDBY / PENDING (${pending.size})",
                            color = WarningAmber,
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (pending.isEmpty()) {
                    item {
                        Text(
                            text = "No pending invitations awaiting response.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    items(pending) { entry ->
                        RiderStatusCard(
                            userName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider",
                            status = "INVITE SENT — STANDBY",
                            color = WarningAmber,
                            icon = Icons.Default.Pending
                        )
                    }
                }

                // 3. DECLINED RIDERS (Previously hidden)
                if (declined.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(TextSecondary))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "DECLINED (${declined.size})",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    items(declined) { entry ->
                        RiderStatusCard(
                            userName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider",
                            status = "DECLINED CONVOY",
                            color = TextSecondary,
                            icon = Icons.Default.Cancel
                        )
                    }
                }
            }

            // CRITICAL FIX: Solo Ride Prohibition Removed — Always Enabled with Dynamic Label!
            val canStart = true
            val startLabel = if (accepted.isNotEmpty()) {
                "START RIDE (${accepted.size + 1} RIDERS)"
            } else {
                "START RIDE SOLO (CONVOY OPEN)"
            }

            TacticalButton(
                text = startLabel,
                onClick = {
                    viewModel.startRide(convoyName, onStartSuccess = onStartRide)
                },
                enabled = canStart,
                color = NeonOrange,
                textColor = if (ThemeState.isDarkTheme) Color.Black else Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
fun RiderStatusCard(
    userName: String,
    status: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp
                )
                Text(
                    text = status,
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = "Status: $status",
                tint = color,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

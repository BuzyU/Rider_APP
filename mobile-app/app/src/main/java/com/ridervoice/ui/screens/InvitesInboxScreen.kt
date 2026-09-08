package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.models.RideInvite
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.InvitesInboxViewModel

@Composable
fun InvitesInboxScreen(
    viewModel: InvitesInboxViewModel = hiltViewModel(),
    onInviteAccepted: (String) -> Unit,
    onBackClick: () -> Unit,
    onHostRideClick: () -> Unit = {}
) {
    val invites by viewModel.invites.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isDark = ThemeState.isDarkTheme

    var inviteToDecline by remember { mutableStateOf<RideInvite?>(null) }
    var processingInviteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadInvites()
    }

    // Confirmation dialog before declining invite
    inviteToDecline?.let { invite ->
        AlertDialog(
            onDismissRequest = { inviteToDecline = null },
            title = {
                Text(
                    text = "DECLINE CONVOY INVITE?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to decline the invitation to '${invite.room.name}' from @${invite.inviter.handle}?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = invite
                        inviteToDecline = null
                        processingInviteId = target.id
                        viewModel.respondToInvite(target, false) {
                            processingInviteId = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("DECLINE INVITE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { inviteToDecline = null }) {
                    Text("CANCEL", color = TextPrimary)
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
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DISPATCH INBOX",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "PENDING INVITES",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
            // Refresh Button
            IconButton(
                onClick = { viewModel.loadInvites() },
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSlate)
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Invites",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Error Banner
        error?.let { err ->
            Surface(
                color = HazardContainer,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FAILED TO UPDATE INVITE: $err",
                        color = AlertRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.loadInvites() }) {
                        Text("RETRY", color = NeonOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isLoading && invites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
            }
        } else if (invites.isEmpty()) {
            // Actionable Empty State
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    color = DarkSlate,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.MailOutline,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "NO PENDING CONVOY INVITES",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "When ride leaders invite you to join a convoy, their transmissions will appear here.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onHostRideClick,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (isDark) Color.Black else Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "HOST YOUR OWN CONVOY",
                                color = if (isDark) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(invites) { invite ->
                    val isProcessing = processingInviteId == invite.id
                    InviteCard(
                        invite = invite,
                        isProcessing = isProcessing,
                        onAccept = {
                            processingInviteId = invite.id
                            viewModel.respondToInvite(invite, true) { roomName ->
                                processingInviteId = null
                                onInviteAccepted(roomName)
                            }
                        },
                        onDecline = {
                            inviteToDecline = invite
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun InviteCard(
    invite: RideInvite,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme

    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(3.dp)).background(NeonOrange))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONVOY INVITATION",
                        color = NeonOrange,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "FREQUENCY LOCK",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = invite.room.name.uppercase(),
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp
            )

            Text(
                text = "Transmitted by @${invite.inviter.handle ?: "Leader"}",
                color = ElectricCyan,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Decline Button
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed.copy(alpha = 0.8f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Decline", tint = AlertRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DECLINE", color = AlertRed, style = MaterialTheme.typography.labelLarge)
                }

                // Accept Button
                Button(
                    onClick = onAccept,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            color = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Accept",
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ACCEPT",
                            color = if (isDark) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

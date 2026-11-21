package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.models.RideInvite
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.components.InlineError
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

    var inviteToDecline by remember { mutableStateOf<RideInvite?>(null) }
    var processingInviteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadInvites()
    }

    inviteToDecline?.let { invite ->
        ConfirmDialog(
            title = stringResource(R.string.inbox_decline_title),
            body = stringResource(R.string.inbox_decline_body, invite.room.name, invite.inviter.handle),
            confirmLabel = stringResource(R.string.action_decline),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                val target = invite
                inviteToDecline = null
                processingInviteId = target.id
                viewModel.respondToInvite(target, false) {
                    processingInviteId = null
                }
            },
            onDismiss = { inviteToDecline = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Editorial Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = Ink,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = stringResource(R.string.menu_inbox_title),
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = { viewModel.loadInvites() },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_invites),
                    tint = Ink,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        error?.let { err ->
            InlineError(
                message = err,
                onRetry = { viewModel.loadInvites() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (isLoading && invites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
            }
        } else if (invites.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    color = PaperRaised,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PaperSunken),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MailOutline,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.inbox_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.inbox_empty_desc),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onHostRideClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Pop),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = PaperOnPop)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.home_create_ride_title),
                                color = PaperOnPop,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = PopContainer,
                    shape = RoundedCornerShape(RvRadius.pill)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Pop)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.inbox_card_badge),
                            color = Pop,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.inbox_status_pending),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = invite.room.name,
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = stringResource(R.string.invited_by_handle, invite.inviter.handle),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Brick.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = BrickContainer.copy(alpha = 0.35f),
                        contentColor = Brick
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_decline), tint = Brick, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_decline), color = Brick, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onAccept,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Pop),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            color = PaperOnPop,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.action_accept),
                            tint = PaperOnPop,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_accept),
                            color = PaperOnPop,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

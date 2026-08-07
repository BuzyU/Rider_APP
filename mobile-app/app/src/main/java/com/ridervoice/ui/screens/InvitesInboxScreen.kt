package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

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
    onBackClick: () -> Unit
) {
    val invites by viewModel.invites.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadInvites()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
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
                text = "INVITES INBOX",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonOrange)
            }
        } else if (invites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pending invites.", color = TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(invites) { invite ->
                    InviteCard(
                        invite = invite,
                        onAccept = {
                            viewModel.respondToInvite(invite, true, onAcceptSuccess = onInviteAccepted)
                        },
                        onDecline = {
                            viewModel.respondToInvite(invite, false, onAcceptSuccess = {})
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
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSlate)
            .padding(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "HOST", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text = "Just now", color = TextSecondary, fontSize = 12.sp) // Simple date formatting mock
        }
        Text(text = "@${invite.inviter.handle}", color = NeonOrange, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "CONVOY", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = invite.room.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onDecline,
                colors = ButtonDefaults.buttonColors(containerColor = Gunmetal),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Decline", tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decline", color = TextPrimary)
            }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Accept", tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accept", color = TextPrimary)
            }
        }
    }
}

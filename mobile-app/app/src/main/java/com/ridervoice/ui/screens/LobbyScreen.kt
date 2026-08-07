package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pending
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
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyViewModel

@Composable
fun LobbyScreen(
    convoyName: String,
    viewModel: LobbyViewModel = hiltViewModel(),
    onStartRide: () -> Unit,
    onBackClick: () -> Unit
) {
    val lobbyStatus by viewModel.lobbyStatus.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startPolling(convoyName)
        onDispose {
            viewModel.stopPolling()
        }
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
                text = "LOBBY: $convoyName",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        if (lobbyStatus == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonOrange)
            }
        } else {
            val status = lobbyStatus!!
            val pending = status.invites.filter { it.status == "PENDING" }
            val accepted = status.invites.filter { it.status == "ACCEPTED" }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("ACCEPTED", color = SuccessGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (accepted.isEmpty()) {
                    item { Text("Waiting for riders to join...", color = TextSecondary, modifier = Modifier.padding(bottom = 16.dp)) }
                } else {
                    items(accepted) { entry ->
                        RiderStatusCard(userName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider", status = "Accepted", color = SuccessGreen)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("PENDING", color = NeonOrange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(pending) { entry ->
                    RiderStatusCard(userName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider", status = "Pending", color = TextSecondary)
                }
            }

            val canStart = accepted.isNotEmpty() // Requires at least one rider

            TacticalButton(
                text = if (canStart) "Start Ride" else "Waiting for Riders...",
                onClick = {
                    viewModel.startRide(convoyName, onStartSuccess = onStartRide)
                },
                enabled = canStart,
                color = NeonOrange,
                textColor = TextPrimary,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
fun RiderStatusCard(userName: String, status: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = userName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = status, color = color, fontSize = 14.sp)
        }
        Icon(
            imageVector = if (status == "Accepted") Icons.Default.CheckCircle else Icons.Default.Pending,
            contentDescription = null,
            tint = color
        )
    }
}

package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.models.Friend
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.InviteFriendsViewModel

@Composable
fun InviteFriendsScreen(
    convoyName: String,
    viewModel: InviteFriendsViewModel = hiltViewModel(),
    onInvitesSent: () -> Unit,
    onBackClick: () -> Unit
) {
    val friends by viewModel.friends.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val invitedIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        viewModel.loadFriends()
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
                text = "INVITE SQUAD",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonOrange)
            }
        } else if (friends.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No friends found. Go add some riders to your squad!", color = TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(friends) { friend ->
                    FriendInviteCard(
                        friend = friend,
                        isInvited = invitedIds.contains(friend.id),
                        onInviteClick = {
                            if (!invitedIds.contains(friend.id)) {
                                viewModel.sendInvite(convoyName, friend.id)
                                invitedIds.add(friend.id)
                            }
                        }
                    )
                }
            }

            TacticalButton(
                text = "Continue to Lobby",
                onClick = onInvitesSent,
                color = ElectricCyan,
                textColor = TextPrimary,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
fun FriendInviteCard(
    friend: Friend,
    isInvited: Boolean,
    onInviteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = friend.displayName ?: friend.handle, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        
        Button(
            onClick = onInviteClick,
            enabled = !isInvited,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isInvited) Gunmetal else NeonOrange,
                disabledContainerColor = Gunmetal
            )
        ) {
            Text(if (isInvited) "Invited" else "Invite", color = if (isInvited) TextSecondary else TextPrimary)
        }
    }
}

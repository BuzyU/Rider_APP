package com.ridervoice.ui.screens

import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.models.Friend
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.InviteFriendsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendsScreen(
    convoyName: String,
    viewModel: InviteFriendsViewModel = hiltViewModel(),
    onInvitesSent: () -> Unit,
    onBackClick: () -> Unit,
    onNavigateToSquad: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {

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
            Column {
                Text(
                    text = "TRANSMIT INVITATIONS",
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

        InviteFriendsContent(
            convoyName = convoyName,
            viewModel = viewModel,
            onInvitesSent = onInvitesSent,
            onNavigateToSquad = onNavigateToSquad,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendsContent(
    convoyName: String,
    viewModel: InviteFriendsViewModel = hiltViewModel(),
    onInvitesSent: (() -> Unit)? = null,
    onNavigateToSquad: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = ThemeState.isDarkTheme

    val friends by viewModel.friends.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val invitedIds = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    var showAddFriendDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadFriends()
    }

    if (showAddFriendDialog) {
        com.ridervoice.ui.components.AddFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onAddFriend = { handle ->
                viewModel.addFriend(handle)
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {

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
                        text = "ERROR: $err",
                        color = AlertRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.loadFriends() }) {
                        Text("RETRY", color = NeonOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showAddFriendDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Rider", tint = NeonOrange, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ CALLSIGN", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
            }

            if (friends.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        friends.forEach { friend ->
                            if (!invitedIds.contains(friend.id)) {
                                viewModel.sendInvite(convoyName, friend.id)
                                invitedIds.add(friend.id)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = "Invite All", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("INVITE ALL", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }
        }

        if (friends.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter squad by @handle", color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = NeonOrange,
                    unfocusedBorderColor = BorderColor,
                    containerColor = DarkSlate,
                    textColor = TextPrimary
                )
            )
        }

        val filteredFriends = remember(friends, searchQuery) {
            if (searchQuery.isBlank()) friends
            else friends.filter { it.handle.contains(searchQuery.removePrefix("@").trim(), ignoreCase = true) }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                }
            } else if (friends.isEmpty()) {

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
                            Icon(Icons.Default.PersonSearch, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "NO SQUAD MEMBERS FOUND",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "You haven't added friends to your squad yet. Commission riders by callsign to transmit invites directly.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddFriendDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = if (isDark) Color.Black else Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ADD RIDER BY CALLSIGN",
                                    color = if (isDark) Color.Black else Color.White,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            if (onNavigateToSquad != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = onNavigateToSquad) {
                                    Text("MANAGE SQUAD ROSTER →", color = ElectricCyan, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredFriends) { friend ->
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
            }
        }

        if (onInvitesSent != null) {
            val buttonLabel = if (invitedIds.isNotEmpty()) {
                "CONTINUE TO LOBBY (${invitedIds.size} INVITED)"
            } else {
                "CONTINUE TO LOBBY (SKIP INVITES)"
            }

            TacticalButton(
                text = buttonLabel,
                onClick = onInvitesSent,
                color = if (invitedIds.isNotEmpty()) TechGreen else NeonOrange,
                textColor = if (isDark) Color.Black else Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
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
    val isDark = ThemeState.isDarkTheme

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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Gunmetal)
                    .border(1.dp, BorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = friend.handle.take(2).uppercase(),
                    color = ElectricCyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.displayName ?: "@${friend.handle}",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp
                )
                Text(
                    text = "@${friend.handle} • ${friend.bikeModel ?: "Motorcycle"}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 11.sp
                )
            }

            Button(
                onClick = onInviteClick,
                enabled = !isInvited,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonOrange,
                    disabledContainerColor = Gunmetal
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (isInvited) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Invited",
                        tint = TechGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "INVITED",
                        color = TechGreen,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    Text(
                        text = "INVITE",
                        color = if (isDark) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

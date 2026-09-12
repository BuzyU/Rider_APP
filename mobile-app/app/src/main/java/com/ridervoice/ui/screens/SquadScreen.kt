package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.Friend
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.SquadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadScreen(
    onBackClick: () -> Unit,
    onJoinRoom: (String) -> Unit = {},
    viewModel: SquadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = ThemeState.isDarkTheme

    val currentUserId = remember {
        FirebaseAuth.getInstance().currentUser?.uid
    }
    var showAddFriendDialog by remember { mutableStateOf(false) }
    var friendToRemove by remember { mutableStateOf<Friend?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(currentUserId) {
        if (currentUserId != null) {
            viewModel.fetchData(currentUserId)
        }
    }

    friendToRemove?.let { friend ->
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            title = {
                Text(
                    text = "REMOVE FROM SQUAD?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Remove @${friend.handle} from your trusted squad directory?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeFriend(friend.id)
                        friendToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("REMOVE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { friendToRemove = null }) {
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
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "COMMUNICATIONS DIRECTORY",
                        color = NeonOrange,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "YOUR SQUAD",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {

                IconButton(
                    onClick = { currentUserId?.let { viewModel.fetchData(it) } },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSlate)
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Squad",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { showAddFriendDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSlate)
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Rider",
                        tint = NeonOrange,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter squad by @handle", color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = BorderColor,
                containerColor = DarkSlate,
                textColor = TextPrimary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        uiState.errorMessage?.let { err ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(8.dp),
                color = HazardContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        color = AlertRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = AlertRed)
                    }
                }
            }
        }

        if (currentUserId == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Sign in to see your squad directory.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Column
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
            }
            return@Column
        }

        if (uiState.invites.isNotEmpty()) {
            Text(
                text = "INCOMING CONVOY INVITES (${uiState.invites.size})",
                color = NeonOrange,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            for (invite in uiState.invites) {
                Surface(
                    color = DarkSlate,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = invite.room.name.uppercase(),
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Invited by @${invite.inviter.handle}",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.acceptInvite(invite) { roomName ->
                                    onJoinRoom(roomName)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "JOIN",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isDark) Color.Black else Color.White
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        val filteredFriends = remember(uiState.friends, searchQuery) {
            if (searchQuery.isBlank()) {
                uiState.friends
            } else {
                val q = searchQuery.removePrefix("@").trim()
                uiState.friends.filter {
                    it.handle.contains(q, ignoreCase = true) ||
                    (it.displayName?.contains(q, ignoreCase = true) == true)
                }
            }
        }

        Text(
            text = "TRUSTED SQUAD (${filteredFriends.size})",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filteredFriends.size) { i ->
                val friend = filteredFriends[i]
                SquadMemberCard(
                    handle = friend.handle,
                    bike = friend.bikeModel ?: "Motorcycle",
                    status = "Tuned In",
                    onRemoveClick = { friendToRemove = friend }
                )
            }
            if (filteredFriends.isEmpty()) {
                item {
                    if (searchQuery.isNotBlank()) {
                        Surface(
                            color = DarkSlate,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No rider matching \"$searchQuery\" in squad.",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        viewModel.addFriend(currentUserId, searchQuery)
                                        searchQuery = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Add @${searchQuery.removePrefix("@").trim()} to Squad",
                                        color = if (isDark) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            color = DarkSlate,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Your squad directory is empty. Tap '+' above to add riders by @handle.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddFriendDialog) {
        com.ridervoice.ui.components.AddFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onAddFriend = { handle ->
                if (currentUserId != null) {
                    viewModel.addFriend(currentUserId, handle)
                }
            }
        )
    }
}

@Composable
fun SquadMemberCard(
    handle: String,
    bike: String,
    status: String,
    onRemoveClick: () -> Unit
) {
    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Gunmetal)
                    .border(1.dp, BorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = handle.take(2).uppercase(),
                    color = ElectricCyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@$handle",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp
                )
                Text(
                    text = bike,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TechGreen))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = status.uppercase(),
                    color = TechGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onRemoveClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = "Remove @$handle from squad",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

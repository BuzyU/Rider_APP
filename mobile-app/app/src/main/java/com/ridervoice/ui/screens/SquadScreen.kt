package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.SquadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadScreen(
    onBackClick: () -> Unit,
    viewModel: SquadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // BUG FIX: was hardcoded to "test-user".
    // Firebase currentUser can be null briefly after a cold start — handle that.
    val currentUserId = remember {
        FirebaseAuth.getInstance().currentUser?.uid
    }
    var showAddFriendDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(currentUserId) {
        if (currentUserId != null) {
            viewModel.fetchData(currentUserId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
            .padding(24.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                }
                Column {
                    Text(
                        text = "YOUR SQUAD",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "MUTUAL TRUSTED RIDERS",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            IconButton(
                onClick = { showAddFriendDialog = true },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSlate)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Rider",
                    tint = NeonOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by @handle", color = TextSecondary) },
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
                unfocusedBorderColor = Gunmetal,
                containerColor = DarkSlate,
                textColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error message banner (non-blocking)
        uiState.errorMessage?.let { err ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = AlertRed.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        color = AlertRed,
                        fontSize = 13.sp,
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

        // Not signed in
        if (currentUserId == null) {
            Text(
                text = "Sign in to see your squad.",
                color = TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            return@Column
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(
                color = NeonOrange,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            return@Column
        }

        // Incoming invites
        if (uiState.invites.isNotEmpty()) {
            Text(
                text = "INCOMING INVITES (${uiState.invites.size})",
                color = NeonOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            for (invite in uiState.invites) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSlate)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = invite.room.name.uppercase(),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Invited by ${invite.inviter.handle}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = { /* Join Room */ },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("JOIN", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Friends list
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
            text = "ONLINE FRIENDS (${filteredFriends.size})",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(filteredFriends.size) { i ->
                val friend = filteredFriends[i]
                SquadMemberCard(
                    handle = friend.handle,
                    bike   = friend.bikeModel ?: "Unknown Bike",
                    status = "Online"
                )
            }
            if (filteredFriends.isEmpty()) {
                item {
                    if (searchQuery.isNotBlank() && currentUserId != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSlate)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Rider \"$searchQuery\" is not in your squad yet.",
                                color = TextSecondary,
                                fontSize = 14.sp
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
                                    "Add @${searchQuery.removePrefix("@").trim()} to Squad",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No friends yet. Search by @handle to add riders.",
                            color = TextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
    
    if (showAddFriendDialog) {
        var friendHandle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddFriendDialog = false },
            title = { Text("Add Rider to Squad", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = friendHandle,
                    onValueChange = { friendHandle = it },
                    placeholder = { Text("@handle", color = TextSecondary) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = Gunmetal,
                        containerColor = DarkSlate,
                        textColor = TextPrimary
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (friendHandle.isNotBlank() && currentUserId != null) {
                        viewModel.addFriend(currentUserId, friendHandle)
                    }
                    showAddFriendDialog = false
                }) {
                    Text("ADD", color = NeonOrange, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFriendDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = GraphiteBase
        )
    }
}

@Composable
fun SquadMemberCard(handle: String, bike: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .clickable { }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Gunmetal),
            contentAlignment = Alignment.Center
        ) {
            Text(handle.take(2).uppercase(), color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "@$handle", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(text = bike, color = TextSecondary, fontSize = 12.sp)
        }
        Text(
            text = status,
            color = if (status == "Online") SuccessGreen else NeonOrange,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Search
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
            value = "",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by @handle", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = Gunmetal,
                containerColor = DarkSlate
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

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

        uiState.errorMessage?.let { err ->
            Text(
                text = "Error: $err",
                color = AlertRed,
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
        Text(
            text = "ONLINE FRIENDS (${uiState.friends.size})",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(uiState.friends.size) { i ->
                val friend = uiState.friends[i]
                SquadMemberCard(
                    handle = friend.handle,
                    bike   = friend.bikeModel ?: "Unknown Bike",
                    status = "Online"
                )
            }
            if (uiState.friends.isEmpty()) {
                item {
                    Text(
                        text = "No friends yet. Search by @handle to add riders.",
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
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

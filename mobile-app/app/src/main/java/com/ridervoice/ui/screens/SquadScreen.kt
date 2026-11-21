package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.Friend
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.components.InlineError
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
        ConfirmDialog(
            title = "Remove from squad?",
            body = "Remove @${friend.handle} from your squad directory?",
            confirmLabel = "Remove",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                viewModel.removeFriend(friend.id)
                friendToRemove = null
            },
            onDismiss = { friendToRemove = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Top Header matching Dashboard & History ("My Squad" + Circular Refresh & Add buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Squad",
                color = Ink,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { currentUserId?.let { viewModel.fetchData(it) } },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PaperRaised)
                        .border(1.dp, InkHairline, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Squad",
                        tint = Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { showAddFriendDialog = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PopContainer)
                        .border(1.dp, Pop.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Rider",
                        tint = Pop,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Rounded Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Filter squad by @handle",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = InkSecondary)
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = InkSecondary)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = Pop,
                unfocusedBorderColor = InkHairline,
                containerColor = PaperRaised,
                textColor = Ink,
                cursorColor = Pop
            ),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        uiState.errorMessage?.let { err ->
            InlineError(
                message = err,
                onRetry = {
                    viewModel.clearError()
                    currentUserId?.let { viewModel.fetchData(it) }
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (currentUserId == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = PaperRaised,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = InkSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Sign in to see your squad",
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            return@Column
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
            }
            return@Column
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.invites.isNotEmpty()) {
                item {
                    Text(
                        text = "INCOMING RIDE INVITES (${uiState.invites.size})",
                        color = Pop,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.9.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                    )
                }

                items(uiState.invites.size) { index ->
                    val invite = uiState.invites[index]
                    Surface(
                        color = PaperRaised,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, Pop.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = invite.room.name,
                                    color = Ink,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Invited by @${invite.inviter.handle}",
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Button(
                                onClick = {
                                    viewModel.acceptInvite(invite) { roomName ->
                                        onJoinRoom(roomName)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Pop,
                                    contentColor = PaperOnPop
                                ),
                                shape = RoundedCornerShape(RvRadius.pill),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text(
                                    text = "Join",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PaperOnPop,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SQUAD MEMBERS",
                        color = InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.9.sp
                    )
                    Text(
                        text = if (filteredFriends.size == 1) "1 rider" else "${filteredFriends.size} riders",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            items(filteredFriends.size) { i ->
                val friend = filteredFriends[i]
                SquadMemberCard(
                    displayName = friend.displayName,
                    handle = friend.handle,
                    bike = friend.bikeModel,
                    onRemoveClick = { friendToRemove = friend }
                )
            }

            if (filteredFriends.isEmpty()) {
                item {
                    Surface(
                        color = PaperRaised,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (searchQuery.isNotBlank()) Icons.Default.PersonSearch else Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = InkSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (searchQuery.isNotBlank()) {
                                val cleanHandle = searchQuery.removePrefix("@").trim()
                                Text(
                                    text = "No rider matching \"$cleanHandle\"",
                                    color = Ink,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Add @$cleanHandle directly to your squad by their handle.",
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        viewModel.addFriend(currentUserId, searchQuery)
                                        searchQuery = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Pop,
                                        contentColor = PaperOnPop
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(
                                        text = "Add @$cleanHandle to Squad",
                                        color = PaperOnPop,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Text(
                                    text = "No squad members yet",
                                    color = Ink,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap the + button above to add riders by @handle and invite them to convoys.",
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { showAddFriendDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Pop,
                                        contentColor = PaperOnPop
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = PaperOnPop,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Add Rider",
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
    bike: String?,
    status: String = "",
    onRemoveClick: () -> Unit,
    displayName: String? = null
) {
    val cleanHandle = handle.removePrefix("@")
    val title = displayName?.takeIf { it.isNotBlank() } ?: "@$cleanHandle"
    val subtitle = buildList {
        if (!displayName.isNullOrBlank()) add("@$cleanHandle")
        if (!bike.isNullOrBlank()) add(bike)
    }.joinToString(" · ")

    val initials = (displayName?.takeIf { it.isNotBlank() } ?: cleanHandle)
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifBlank { cleanHandle.take(2).uppercase() }

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperSunken)
                    .border(1.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = "Remove @$cleanHandle from squad",
                    tint = InkSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

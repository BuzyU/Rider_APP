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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.models.Friend
import com.ridervoice.ui.components.InlineError
import com.ridervoice.ui.components.PrimaryButton
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
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(48.dp)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.invite_riders_title),
                    color = Ink,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = convoyName,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

    Column(modifier = modifier.fillMaxWidth()) {
        error?.let { err ->
            InlineError(
                message = err,
                onRetry = { viewModel.loadFriends() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { showAddFriendDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, InkHairline),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = PaperRaised)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.cd_add_rider), tint = Pop, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.invite_add_by_handle), style = MaterialTheme.typography.labelMedium, color = Ink, fontWeight = FontWeight.SemiBold)
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
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = PaperRaised)
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = stringResource(R.string.cd_invite_all), tint = Ink, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.invite_all_label), style = MaterialTheme.typography.labelMedium, color = Ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (friends.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.squad_filter_placeholder), color = InkSecondary, style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = InkSecondary) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Pop,
                    unfocusedBorderColor = InkHairline,
                    containerColor = PaperRaised,
                    textColor = Ink,
                    cursorColor = Pop
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
                    CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
                }
            } else if (friends.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
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
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PersonSearch, contentDescription = null, tint = InkSecondary, modifier = Modifier.size(26.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.squad_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Ink
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.invite_empty_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddFriendDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Pop),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PaperOnPop)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.invite_add_rider_by_handle),
                                    color = PaperOnPop,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (onNavigateToSquad != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = onNavigateToSquad) {
                                    Text(stringResource(R.string.invite_manage_squad), color = Pop, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                    items(filteredFriends, key = { it.id }) { friend ->
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
                stringResource(R.string.invite_continue_with_count, invitedIds.size)
            } else {
                stringResource(R.string.device_continue_to_lobby)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                PrimaryButton(
                    text = buttonLabel,
                    onClick = onInvitesSent
                )
            }
        }
    }
}

@Composable
fun FriendInviteCard(
    friend: Friend,
    isInvited: Boolean,
    onInviteClick: () -> Unit
) {
    val cleanHandle = friend.handle.removePrefix("@")
    val subtitle = buildList {
        add("@$cleanHandle")
        friend.bikeModel?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PaperSunken)
                    .border(1.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = cleanHandle.take(2).uppercase(),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.displayName?.takeIf { it.isNotBlank() } ?: "@$cleanHandle",
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onInviteClick,
                enabled = !isInvited,
                shape = RoundedCornerShape(RvRadius.pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Pop,
                    contentColor = PaperOnPop,
                    disabledContainerColor = MossContainer,
                    disabledContentColor = Moss
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                if (isInvited) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.lobby_section_invited),
                        tint = Moss,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.lobby_section_invited),
                        color = Moss,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = stringResource(R.string.action_invite),
                        color = PaperOnPop,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

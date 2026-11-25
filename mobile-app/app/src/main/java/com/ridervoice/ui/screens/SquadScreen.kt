package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.models.Friend
import com.ridervoice.models.SentSquadRequest
import com.ridervoice.ui.components.AddFriendDialog
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.components.InlineError
import com.ridervoice.ui.components.RvPullToRefreshContainer
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.SquadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadScreen(
    onBackClick: () -> Unit,
    onJoinRoom: (String) -> Unit = {},
    onOpenRequests: () -> Unit = {},
    viewModel: SquadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val currentUserId = remember {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }
    var showAddFriendDialog by remember { mutableStateOf(false) }
    var friendToRemove by remember { mutableStateOf<Friend?>(null) }

    // Initial load ONLY when cache is cold (switching tabs never reloads or flashes spinner)
    LaunchedEffect(currentUserId) {
        if (currentUserId != null) {
            viewModel.ensureLoaded(currentUserId)
        }
    }

    // Lifecycle listener: silently sync when app returns from background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentUserId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentUserId != null) {
                viewModel.onForegroundResume(currentUserId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    friendToRemove?.let { friend ->
        ConfirmDialog(
            title = stringResource(R.string.squad_remove_title),
            body = stringResource(R.string.squad_remove_body, friend.handle.removePrefix("@")),
            confirmLabel = stringResource(R.string.action_remove),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                if (currentUserId != null) {
                    viewModel.removeFriend(currentUserId, friend.id)
                } else {
                    viewModel.removeFriend(friend.id)
                }
                friendToRemove = null
            },
            onDismiss = { friendToRemove = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Top Header: "My Squad" + Moss green PersonAdd button (no Refresh button, no SOS-like color)
            CapChromeFontScale(maxScale = 1.3f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tab_my_squad),
                        color = Ink,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            viewModel.resetSearch()
                            showAddFriendDialog = true
                        },
                        modifier = Modifier
                            .size(RvTouchTarget.min)
                            .clip(CircleShape)
                            .background(MossContainer)
                            .border(1.dp, Moss.copy(alpha = 0.4f), CircleShape)
                            .testTag("squad_add_rider_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = stringResource(R.string.cd_add_rider),
                            tint = Moss,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            uiState.errorMessage?.let { err ->
                InlineError(
                    message = err,
                    onRetry = {
                        viewModel.clearError()
                        currentUserId?.let { viewModel.pullToRefresh(it) }
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
                                text = stringResource(R.string.squad_sign_in_prompt),
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                return@Column
            }

            if (uiState.isLoading && !uiState.hasLoadedOnce) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Moss, strokeWidth = 3.dp)
                }
                return@Column
            }

            val hasRejectedRequests = uiState.sentRequests.any { it.isRejected }

            RvPullToRefreshContainer(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.pullToRefresh(currentUserId) },
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 2.dp,
                        bottom = if (uiState.incomingRequests.isNotEmpty()) 88.dp else 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "header_members") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.squad_members_header),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp
                            )
                            Text(
                                text = pluralStringResource(R.plurals.riders_count, uiState.friends.size, uiState.friends.size),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    items(uiState.friends, key = { "friend_${it.id}" }) { friend ->
                        SquadMemberCard(
                            displayName = friend.displayName,
                            handle = friend.handle,
                            bike = friend.bikeModel,
                            onRemoveClick = { friendToRemove = friend }
                        )
                    }

                    if (uiState.friends.isEmpty() && uiState.sentRequests.isEmpty()) {
                        item(key = "empty_squad") {
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
                                            imageVector = Icons.Default.Groups,
                                            contentDescription = null,
                                            tint = InkSecondary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.squad_empty_title),
                                        color = Ink,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.squad_empty_desc),
                                        color = InkSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            viewModel.resetSearch()
                                            showAddFriendDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Moss,
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
                                            text = stringResource(R.string.cd_add_rider),
                                            color = PaperOnPop,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Sent Requests section after a clean dashed divider
                    if (uiState.sentRequests.isNotEmpty()) {
                        item(key = "pending_dashed_divider") {
                            PendingSectionDashedDivider(
                                showClearRejected = hasRejectedRequests,
                                onClearRejected = { viewModel.clearRejectedRequests(currentUserId) },
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                        }

                        items(uiState.sentRequests, key = { "sent_${it.id}" }) { sentReq ->
                            SentSquadRequestCard(
                                request = sentReq,
                                onRemoveRejected = {
                                    viewModel.dismissSentRequest(currentUserId, sentReq.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Bottom-right Requests button: ONLY exists when there is at least one incoming request!
        if (uiState.incomingRequests.isNotEmpty()) {
            Surface(
                color = CharcoalSurface,
                shape = RoundedCornerShape(RvRadius.pill),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(RvRadius.pill))
                    .clickable(onClick = onOpenRequests)
                    .testTag("squad_requests_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MarkEmailUnread,
                        contentDescription = stringResource(R.string.cd_open_squad_requests),
                        tint = PaperOnPop,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.squad_requests_title),
                        color = PaperOnPop,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .clip(CircleShape)
                            .background(Pop)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.incomingRequests.size.toString(),
                            color = PaperOnPop,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showAddFriendDialog && currentUserId != null) {
        AddFriendDialog(
            onDismiss = {
                showAddFriendDialog = false
                viewModel.resetSearch()
            },
            searchState = uiState.searchState,
            isSendingRequest = uiState.isSendingRequest,
            onSearchQueryChange = { q, immediate ->
                viewModel.onSearchQueryChanged(q, currentUserId, immediate)
            },
            onSendRequestToProfile = { profile ->
                viewModel.sendSquadRequest(currentUserId, profile) {
                    showAddFriendDialog = false
                    viewModel.resetSearch()
                }
            },
            onAcceptIncomingProfile = { profile ->
                viewModel.acceptIncomingByProfile(currentUserId, profile) {
                    showAddFriendDialog = false
                    viewModel.resetSearch()
                }
            }
        )
    }
}

@Composable
private fun PendingSectionDashedDivider(
    showClearRejected: Boolean,
    onClearRejected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("squad_pending_section_divider"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.squad_pending_section),
            color = InkSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(10.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
        ) {
            val dashColor = InkHairline
            drawLine(
                color = dashColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            )
        }
        if (showClearRejected) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.squad_clear_rejected),
                color = Brick,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .defaultMinSize(minHeight = 48.dp).clickable(onClick = onClearRejected)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("squad_clear_rejected_btn")
            )
        }
    }
}

@Composable
private fun SentSquadRequestCard(
    request: SentSquadRequest,
    onRemoveRejected: () -> Unit
) {
    val cleanHandle = request.handle.removePrefix("@")
    val title = request.displayName?.takeIf { it.isNotBlank() } ?: "@$cleanHandle"
    val subtitle = buildList {
        if (!request.displayName.isNullOrBlank()) add("@$cleanHandle")
        if (!request.bikeModel.isNullOrBlank()) add(request.bikeModel)
    }.joinToString(" · ")

    val initials = (request.displayName?.takeIf { it.isNotBlank() } ?: cleanHandle)
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifBlank { cleanHandle.take(2).uppercase() }

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            1.dp,
            if (request.isRejected) Brick.copy(alpha = 0.25f) else InkHairline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sent_request_card_${request.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PaperSunken)
                    .border(1.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (request.isRejected) {
                Surface(
                    color = BrickContainer,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = BorderStroke(1.dp, Brick.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = stringResource(R.string.squad_status_rejected),
                        color = Brick,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onRemoveRejected,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(BrickContainer.copy(alpha = 0.45f))
                        .testTag("sent_request_remove_${request.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_remove_rejected_request, cleanHandle),
                        tint = Brick,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Surface(
                    color = PaperSunken,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = BorderStroke(1.dp, InkHairline)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(InkSecondary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.squad_status_pending),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
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
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PaperSunken)
                    .border(1.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BrickContainer.copy(alpha = 0.4f))
                    .testTag("squad_remove_$cleanHandle")
            ) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = stringResource(R.string.cd_remove_from_squad, cleanHandle),
                    tint = Brick,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

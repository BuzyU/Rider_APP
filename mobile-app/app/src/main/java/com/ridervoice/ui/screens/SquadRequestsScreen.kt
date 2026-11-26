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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
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
import com.ridervoice.models.IncomingSquadRequest
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.InlineError
import com.ridervoice.ui.components.RvPullToRefreshContainer
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.SquadViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SquadRequestsScreen(
    onBackClick: () -> Unit,
    viewModel: SquadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUserId = remember {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(currentUserId) {
        if (currentUserId != null) {
            viewModel.ensureLoaded(currentUserId)
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Editorial Top Bar
        CapChromeFontScale(maxScale = 1.3f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(RvTouchTarget.min)
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

                Text(
                    text = stringResource(R.string.squad_requests_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )

                if (uiState.incomingRequests.isNotEmpty()) {
                    Surface(
                        color = PopContainer,
                        shape = RoundedCornerShape(RvRadius.pill),
                        border = BorderStroke(1.dp, Pop.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = uiState.incomingRequests.size.toString(),
                            color = Pop,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        RvPullToRefreshContainer(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { currentUserId?.let { viewModel.pullToRefresh(it) } },
            modifier = Modifier.weight(1f)
        ) {
            if (uiState.incomingRequests.isEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
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
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(PaperSunken),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MarkEmailRead,
                                        contentDescription = null,
                                        tint = InkSecondary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = stringResource(R.string.squad_requests_empty_title),
                                    color = Ink,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.squad_requests_empty_desc),
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.incomingRequests, key = { it.id }) { req ->
                        val isProcessing = req.id in uiState.processingRequestIds
                        IncomingSquadRequestCard(
                            request = req,
                            isProcessing = isProcessing,
                            onAccept = {
                                if (currentUserId != null) {
                                    viewModel.acceptIncomingRequest(currentUserId, req)
                                }
                            },
                            onDecline = {
                                if (currentUserId != null) {
                                    viewModel.declineIncomingRequest(currentUserId, req)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingSquadRequestCard(
    request: IncomingSquadRequest,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val cleanHandle = request.requester?.handle?.removePrefix("@").orEmpty()
    val displayName = request.requester?.displayName?.takeIf { it.isNotBlank() }
    val title = displayName ?: if (cleanHandle.isNotBlank()) "@$cleanHandle" else request.requesterId
    val subtitle = buildList {
        if (displayName != null && cleanHandle.isNotBlank()) add("@$cleanHandle")
        val bike = request.requester?.bikeModel?.takeIf { it.isNotBlank() }
        if (bike != null) add(bike)
    }.joinToString(" · ")

    val initials = (displayName ?: cleanHandle)
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifBlank { "R" }

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("incoming_request_card_${request.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
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
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle.ifBlank { stringResource(R.string.squad_request_sent_incoming) },
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (isProcessing) {
                CircularProgressIndicator(
                    color = Moss,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .size(22.dp)
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(BrickContainer.copy(alpha = 0.65f))
                            .border(1.dp, Brick.copy(alpha = 0.35f), CircleShape)
                            .testTag("request_decline_${request.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_decline_squad_request, cleanHandle.ifBlank { title }),
                            tint = Brick,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MossContainer)
                            .border(1.dp, Moss.copy(alpha = 0.45f), CircleShape)
                            .testTag("request_accept_${request.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_accept_squad_request, cleanHandle.ifBlank { title }),
                            tint = Moss,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

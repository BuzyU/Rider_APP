package com.ridervoice.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.ui.components.*
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    convoyName: String,
    viewModel: LobbyViewModel = hiltViewModel(),
    homeViewModel: com.ridervoice.ui.viewmodels.HomeViewModel = hiltViewModel(),
    onStartRide: (isHost: Boolean) -> Unit,
    onBackClick: () -> Unit,
    onInviteMoreClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val lobbyStatus by viewModel.lobbyStatus.collectAsState()
    val error by viewModel.error.collectAsState()
    val terminal by viewModel.terminal.collectAsState()

    var showExitDialog by remember { mutableStateOf(false) }
    var showStartAloneDialog by remember { mutableStateOf(false) }
    var showAddRidersSheet by remember { mutableStateOf(false) }
    var riderToRemove by remember { mutableStateOf<com.ridervoice.models.LobbyRiderInfo?>(null) }

    BackHandler {
        showExitDialog = true
    }

    DisposableEffect(Unit) {
        viewModel.startPolling(convoyName)
        onDispose {
            viewModel.stopPolling()
        }
    }

    val navigatedToRide = androidx.compose.runtime.saveable.rememberSaveable(lobbyStatus?.roomId ?: convoyName) {
        mutableStateOf(false)
    }

    LaunchedEffect(lobbyStatus?.roomId, lobbyStatus?.isRideStarted) {
        if (lobbyStatus?.isRideStarted == true && !navigatedToRide.value) {
            navigatedToRide.value = true
            val isHost = lobbyStatus?.isHost == true
            onStartRide(isHost)
        }
    }

    // Register callsigns with ParticipantNameResolver whenever invites update
    LaunchedEffect(lobbyStatus?.invites) {
        lobbyStatus?.invites?.forEach { entry ->
            val name = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider"
            ParticipantNameResolver.register(entry.invitee.id, name)
        }
    }

    // Terminal state dialogs
    terminal?.let { term ->
        val (termTitle, termBody) = when (term) {
            LobbyViewModel.Terminal.ENDED -> "Ride ended" to "The host ended this ride."
            LobbyViewModel.Terminal.REMOVED -> "Removed from ride" to "You were removed from this ride."
            LobbyViewModel.Terminal.NOT_FOUND -> "Ride not found" to "This ride no longer exists."
        }
        ConfirmDialog(
            title = termTitle,
            body = termBody,
            confirmLabel = "Go home",
            dismissLabel = "Close",
            onConfirm = onBackClick,
            onDismiss = onBackClick
        )
    }

    if (showExitDialog) {
        val isHost = lobbyStatus?.isHost == true
        ConfirmDialog(
            title = if (isHost) "Leave ride lobby?" else "Leave this ride?",
            body = if (isHost) "Leaving the lobby will stop monitoring rider invitations. You can rejoin or start later." else "You will leave this ride lobby.",
            confirmLabel = "Leave",
            dismissLabel = "Stay",
            onConfirm = {
                showExitDialog = false
                val roomRef = lobbyStatus?.roomId ?: convoyName
                viewModel.leaveRoom(roomRef) {
                    onBackClick()
                }
            },
            onDismiss = { showExitDialog = false },
            destructive = true
        )
    }

    if (showStartAloneDialog) {
        ConfirmDialog(
            title = stringResource(R.string.lobby_start_alone_prompt),
            body = stringResource(R.string.lobby_start_alone_desc),
            confirmLabel = "Start anyway",
            dismissLabel = "Wait for riders",
            onConfirm = {
                showStartAloneDialog = false
                viewModel.startRide(convoyName, onStartSuccess = {
                    onStartRide(true)
                })
            },
            onDismiss = { showStartAloneDialog = false }
        )
    }

    riderToRemove?.let { rider ->
        val name = rider.displayName ?: rider.handle ?: "rider"
        ConfirmDialog(
            title = "Remove $name?",
            body = "Are you sure you want to remove $name from this ride?",
            confirmLabel = "Remove",
            dismissLabel = "Cancel",
            onConfirm = {
                val uid = rider.id
                riderToRemove = null
                viewModel.removeRider(convoyName, uid)
            },
            onDismiss = { riderToRemove = null },
            destructive = true
        )
    }

    if (showAddRidersSheet) {
        com.ridervoice.ui.components.AddRidersBottomSheet(
            convoyName = convoyName,
            lobbyViewModel = viewModel,
            onDismiss = { showAddRidersSheet = false }
        )
    }

    val status = lobbyStatus
    val isHost = status?.isHost == true
    val accepted = status?.invites?.filter { it.status == "ACCEPTED" } ?: emptyList()
    val pending = status?.invites?.filter { it.status == "PENDING" } ?: emptyList()
    val declined = status?.invites?.filter { it.status == "DECLINED" } ?: emptyList()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = convoyName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Waiting to start",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningAmber
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { showExitDialog = true },
                        modifier = Modifier.size(RvTouchTarget.min)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Leave Lobby",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showExitDialog = true },
                        modifier = Modifier.size(RvTouchTarget.min)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Leave",
                            tint = AlertRed
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = GraphiteBase,
                    titleContentColor = TextPrimary
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            Surface(
                color = GraphiteBase,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RvSpacing.lg, vertical = RvSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                ) {
                    if (isHost) {
                        PrimaryButton(
                            text = if (accepted.isNotEmpty()) "Start ride (${accepted.size + 1} riders)" else "Start ride",
                            onClick = {
                                if (accepted.isEmpty()) {
                                    showStartAloneDialog = true
                                } else {
                                    viewModel.startRide(convoyName, onStartSuccess = {
                                        onStartRide(true)
                                    })
                                }
                            }
                        )
                        SecondaryButton(
                            text = stringResource(R.string.action_invite_friends),
                            onClick = {
                                showAddRidersSheet = true
                                onInviteMoreClick()
                            },
                            leadingIcon = Icons.Default.PersonAdd
                        )
                    } else {
                        Surface(
                            color = DarkSlate,
                            shape = RoundedCornerShape(RvRadius.md),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TechGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(RvSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = TechGreen,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(RvSpacing.md))
                                Text(
                                    text = stringResource(R.string.lobby_waiting_host),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TechGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        SecondaryButton(
                            text = "Leave ride",
                            onClick = { showExitDialog = true },
                            destructive = true
                        )
                    }
                }
            }
        },
        containerColor = GraphiteBase
    ) { innerPadding ->
        if (status == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(RvSpacing.md))
                    Text(
                        text = stringResource(R.string.lobby_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = RvSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(RvSpacing.md),
                contentPadding = PaddingValues(vertical = RvSpacing.md)
            ) {
                // Polling / Reconnecting banner (small inline, never FAILED TO START)
                if (error != null) {
                    item {
                        Surface(
                            color = Gunmetal,
                            shape = RoundedCornerShape(RvRadius.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = WarningAmber,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(RvSpacing.sm))
                                Text(
                                    text = "Reconnecting...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningAmber
                                )
                            }
                        }
                    }
                }

                // Ride Code Card (CodeCard)
                val joinCode = status.joinCode ?: ""
                if (joinCode.isNotBlank()) {
                    item {
                        CodeCard(
                            code = joinCode,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Ride Code", joinCode)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Ride code copied", Toast.LENGTH_SHORT).show()
                            },
                            onShare = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Join my ride '$convoyName' on RiderVoice using code: $joinCode\nhttps://ridervoice.app/j/$joinCode")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share ride code"))
                            }
                        )
                    }
                }

                // Section 1: Joined (n)
                item {
                    Text(
                        text = "Joined (${accepted.size + 1})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TechGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Host row
                item {
                    RiderRow(
                        name = "You",
                        subtitle = if (isHost) "Ride Host" else "Joined",
                        status = if (isHost) "Host" else "Joined"
                    )
                }

                if (accepted.isEmpty() && !isHost) {
                    item {
                        Text(
                            text = stringResource(R.string.lobby_empty_joined),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    items(accepted) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider"
                        RiderRow(
                            name = riderName,
                            subtitle = entry.invitee.bikeModel,
                            status = "Joined",
                            trailing = if (isHost) {
                                {
                                    IconButton(onClick = { riderToRemove = entry.invitee }) {
                                        Icon(
                                            imageVector = Icons.Default.RemoveCircleOutline,
                                            contentDescription = "Remove",
                                            tint = AlertRed
                                        )
                                    }
                                }
                            } else null
                        )
                    }
                }

                // Section 2: Invited (n)
                if (pending.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(RvSpacing.sm))
                        Text(
                            text = "Invited (${pending.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(pending) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider"
                        RiderRow(
                            name = riderName,
                            subtitle = entry.invitee.bikeModel,
                            status = "Invited",
                            trailing = if (isHost) {
                                {
                                    IconButton(onClick = { riderToRemove = entry.invitee }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel invite",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            } else null
                        )
                    }
                }

                // Section 3: Declined (n)
                if (declined.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(RvSpacing.sm))
                        Text(
                            text = "Declined (${declined.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(declined) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider"
                        RiderRow(
                            name = riderName,
                            status = "Declined"
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(RvSpacing.xl))
                }
            }
        }
    }
}

package com.ridervoice.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ridervoice.mesh.link.ActiveOfflineTransportMode
import com.ridervoice.mesh.link.WifiDirectStatus
import com.ridervoice.mesh.link.aware.WifiAwareStatus
import com.ridervoice.mesh.offline.RiderLinkLevel
import com.ridervoice.permissions.PermissionManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.mesh.RoomMeshConfig
import com.ridervoice.ui.components.*
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyLoadState
import com.ridervoice.ui.viewmodels.LobbyViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import com.ridervoice.permissions.LocationGateStatus
import com.ridervoice.permissions.MandatoryLocationGate
import com.ridervoice.ui.components.LocalRvFeedback
import com.ridervoice.ui.screens.map.ConvoyPreStartMapOverlay

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
    val feedback = LocalRvFeedback.current
    val lobbyStatus by viewModel.lobbyStatus.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val activeRide by viewModel.activeRideState.collectAsStateWithLifecycle()
    val wifiDirectFlow: kotlinx.coroutines.flow.StateFlow<*> = viewModel.wifiDirectStatus
    val rawWifiDirectStatus by wifiDirectFlow.collectAsStateWithLifecycle()
    val wifiDirectStatus = (rawWifiDirectStatus as? WifiDirectStatus) ?: WifiDirectStatus.IDLE
    val selectedGoFlow: kotlinx.coroutines.flow.StateFlow<*> = viewModel.selectedGoId
    val rawSelectedGoId by selectedGoFlow.collectAsStateWithLifecycle()
    val selectedGoId = rawSelectedGoId as? String
    val wifiAwareStatusFlow: kotlinx.coroutines.flow.StateFlow<*> = viewModel.wifiAwareStatus
    val rawWifiAwareStatus by wifiAwareStatusFlow.collectAsStateWithLifecycle()
    val wifiAwareStatus = (rawWifiAwareStatus as? WifiAwareStatus) ?: WifiAwareStatus.IDLE
    val awarePeerCountFlow: kotlinx.coroutines.flow.StateFlow<*> = viewModel.wifiAwareActivePeerCount
    val rawAwarePeerCount by awarePeerCountFlow.collectAsStateWithLifecycle()
    val wifiAwarePeerCount = (rawAwarePeerCount as? Int) ?: 0
    val activeTransportFlow: kotlinx.coroutines.flow.StateFlow<*> = viewModel.activeOfflineTransportMode
    val rawActiveTransport by activeTransportFlow.collectAsStateWithLifecycle()
    val activeOfflineTransportMode = (rawActiveTransport as? ActiveOfflineTransportMode) ?: ActiveOfflineTransportMode.MANUAL_OR_NONE
    val error by viewModel.error.collectAsStateWithLifecycle()
    val terminal by viewModel.terminal.collectAsStateWithLifecycle()

    val nearbyWifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.retryWifiDirect()
    }
    LaunchedEffect(lobbyStatus?.roomId, lobbyStatus?.offlineMeshEnabled, activeRide?.meshConfig?.optOut) {
        val wantMesh = (lobbyStatus?.offlineMeshEnabled ?: true) && (activeRide?.meshConfig?.optOut != true)
        if (wantMesh) {
            val missing = PermissionManager.nearbyWifiPermissions.filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                nearbyWifiLauncher.launch(missing.toTypedArray())
            } else {
                viewModel.retryWifiDirect()
            }
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var showStartAloneDialog by remember { mutableStateOf(false) }
    var showAddRidersSheet by remember { mutableStateOf(false) }
    var riderToRemove by remember { mutableStateOf<com.ridervoice.models.LobbyRiderInfo?>(null) }
    var showLobbyMap by rememberSaveable { mutableStateOf(false) }
    val (locationStatus, refreshLocationStatus) = rememberMandatoryLocationStatus()
    var showLocationGateDialog by remember { mutableStateOf(false) }
    var pendingStartRideAllowSolo by remember { mutableStateOf<Boolean?>(null) }

    BackHandler {
        showExitDialog = true
    }

    DisposableEffect(Unit) {
        viewModel.startPolling(convoyName)
        onDispose {
            viewModel.stopPolling()
        }
    }

    val currentRoomRef = lobbyStatus?.roomId ?: activeRide?.roomId ?: convoyName
    val navigatedToRide = androidx.compose.runtime.saveable.rememberSaveable(currentRoomRef) {
        mutableStateOf(false)
    }

    LaunchedEffect(lobbyStatus?.roomId, lobbyStatus?.isRideStarted, locationStatus) {
        if (lobbyStatus?.isRideStarted == true && !navigatedToRide.value) {
            if (locationStatus == LocationGateStatus.READY) {
                navigatedToRide.value = true
                val isHost = lobbyStatus?.isHost == true
                onStartRide(isHost)
            } else {
                showLocationGateDialog = true
            }
        }
    }

    LaunchedEffect(locationStatus) {
        if (locationStatus == LocationGateStatus.READY && showLocationGateDialog) {
            showLocationGateDialog = false
            val allowSolo = pendingStartRideAllowSolo
            pendingStartRideAllowSolo = null
            if (allowSolo != null) {
                viewModel.startRide(convoyName, allowSolo = allowSolo, onStartSuccess = {
                    onStartRide(true)
                })
            }
        }
    }

    if (showLocationGateDialog && locationStatus != LocationGateStatus.READY) {
        MandatoryLocationGateDialog(
            status = locationStatus,
            onDismiss = {
                pendingStartRideAllowSolo = null
                showLocationGateDialog = false
            },
            onReady = {
                refreshLocationStatus()
                showLocationGateDialog = false
                val allowSolo = pendingStartRideAllowSolo
                pendingStartRideAllowSolo = null
                if (allowSolo != null) {
                    viewModel.startRide(convoyName, allowSolo = allowSolo, onStartSuccess = {
                        onStartRide(true)
                    })
                }
            }
        )
    }

    // Register callsigns with ParticipantNameResolver whenever invites update
    LaunchedEffect(lobbyStatus?.invites) {
        lobbyStatus?.invites?.forEach { entry ->
            val name = entry.invitee.displayName ?: entry.invitee.handle ?: "Rider"
            ParticipantNameResolver.register(entry.invitee.id, name, rideId = convoyName)
        }
    }

    // Terminal state dialogs
    terminal?.let { term ->
        val (termTitle, termBody) = when (term) {
            LobbyViewModel.Terminal.ENDED -> stringResource(R.string.lobby_term_ended_title) to stringResource(R.string.lobby_term_ended_body)
            LobbyViewModel.Terminal.REMOVED -> stringResource(R.string.lobby_term_removed_title) to stringResource(R.string.lobby_term_removed_body)
            LobbyViewModel.Terminal.NOT_FOUND -> stringResource(R.string.lobby_term_not_found_title) to stringResource(R.string.lobby_term_not_found_body)
        }
        ConfirmDialog(
            title = termTitle,
            body = termBody,
            confirmLabel = stringResource(R.string.action_go_home),
            dismissLabel = stringResource(R.string.action_close),
            onConfirm = onBackClick,
            onDismiss = onBackClick
        )
    }

    if (showExitDialog) {
        val isHost = lobbyStatus?.isHost ?: activeRide?.isHost ?: true
        ConfirmDialog(
            title = if (isHost) stringResource(R.string.lobby_exit_host_title) else stringResource(R.string.action_leave_ride),
            body = if (isHost) stringResource(R.string.lobby_exit_host_body) else stringResource(R.string.lobby_exit_rider_body),
            confirmLabel = stringResource(R.string.action_leave),
            dismissLabel = stringResource(R.string.action_stay),
            onConfirm = {
                showExitDialog = false
                val roomRef = lobbyStatus?.roomId ?: activeRide?.roomId ?: convoyName
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
            confirmLabel = stringResource(R.string.lobby_start_anyway),
            dismissLabel = stringResource(R.string.lobby_wait_for_riders),
            onConfirm = {
                showStartAloneDialog = false
                if (MandatoryLocationGate.evaluate(context) == LocationGateStatus.READY) {
                    viewModel.startRide(convoyName, allowSolo = true, onStartSuccess = {
                        onStartRide(true)
                    })
                } else {
                    refreshLocationStatus()
                    pendingStartRideAllowSolo = true
                    showLocationGateDialog = true
                }
            },
            onDismiss = { showStartAloneDialog = false }
        )
    }

    riderToRemove?.let { rider ->
        val name = rider.displayName ?: rider.handle ?: stringResource(R.string.participant_rider_prefix)
        ConfirmDialog(
            title = stringResource(R.string.lobby_remove_rider_title, name),
            body = stringResource(R.string.lobby_remove_rider_body, name),
            confirmLabel = stringResource(R.string.action_remove),
            dismissLabel = stringResource(R.string.action_cancel),
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
    val effectiveDisplayName = status?.convoyName ?: activeRide?.displayName ?: convoyName
    val effectiveJoinCode = status?.joinCode ?: activeRide?.joinCode ?: ""
    val isHost = status?.isHost ?: activeRide?.isHost ?: true
    val accepted = status?.invites?.filter { it.status == "ACCEPTED" } ?: emptyList()
    val pending = status?.invites?.filter { it.status == "PENDING" } ?: emptyList()
    val declined = status?.invites?.filter { it.status == "DECLINED" } ?: emptyList()
    val isFailedWithNoData = status == null && activeRide == null && loadState is LobbyLoadState.Failed

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = ZeroWindowInsets,
        topBar = {
            TopAppBar(
                modifier = Modifier.rvHorizontalSafeArea(),
                title = {
                    Column {
                        Text(
                            text = effectiveDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = when {
                                loadState is LobbyLoadState.Failed -> stringResource(R.string.lobby_failed_title)
                                loadState is LobbyLoadState.Retrying -> {
                                    val r = loadState as LobbyLoadState.Retrying
                                    stringResource(R.string.lobby_retrying, r.attempt, r.maxAttempts)
                                }
                                status == null -> stringResource(R.string.lobby_connecting)
                                else -> stringResource(R.string.lobby_waiting_to_start)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (loadState is LobbyLoadState.Failed) Brick else InkSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { showExitDialog = true },
                        modifier = Modifier
                            .padding(start = 8.dp, end = 4.dp)
                            .size(RvTouchTarget.min)
                            .clip(CircleShape)
                            .background(PaperRaised)
                            .border(1.dp, InkHairline, CircleShape)
                    ) {
                        Icon(
                            imageVector = RvIcons.Back,
                            contentDescription = stringResource(R.string.cd_leave_lobby),
                            tint = Ink,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink
                ),
                windowInsets = RvSafeTopInsets
            )
        },
        bottomBar = {
            if (!isFailedWithNoData) {
                Surface(
                    color = Paper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .rvBottomSafeArea()
                        .imePadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RvSpacing.lg, vertical = RvSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                    ) {
                        if (isHost) {
                            val meshConfig = activeRide?.meshConfig
                            val isOfflineEnabled = meshConfig?.offlineMeshEnabled ?: (status?.offlineMeshEnabled ?: true)
                            val readyMeshCount = (if (isOfflineEnabled && meshConfig?.optOut != true) 1 else 0) +
                                accepted.count { isOfflineEnabled && it.offlineConfigStatus == "READY" }
                            if (readyMeshCount > com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS) {
                                Text(
                                    text = stringResource(R.string.mesh_convoy_unavailable_gt_six),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Brick,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            val riderStatuses = accepted.map { inv ->
                                com.ridervoice.mesh.offline.LobbyRiderStatus(
                                    riderId = inv.invitee.id,
                                    status = inv.offlineConfigStatus ?: "CONFIGURING",
                                    isPresent = inv.isPresent
                                )
                            }
                            val gateResult = com.ridervoice.mesh.offline.StartRideGate.evaluate(
                                hostState = com.ridervoice.mesh.offline.OfflineConfigState(
                                    status = meshConfig?.status ?: com.ridervoice.mesh.offline.OfflineConfigStatus.NOT_STARTED,
                                    optOut = meshConfig?.optOut ?: false
                                ),
                                roomOfflineMeshEnabled = isOfflineEnabled,
                                acceptedRiders = riderStatuses,
                                allowSolo = accepted.isEmpty()
                            )

                            if (!gateResult.isOpen) {
                                // FX-03 / V5: Start button is NEVER composed until gate opens
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = RvSpacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                                    Text(
                                        text = stringResource(
                                            R.string.lobby_configuring_offline_progress,
                                            gateResult.readyRidersCount,
                                            gateResult.totalRidersCount
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                PrimaryButton(
                                    modifier = Modifier.testTag("lobby_start_button"),
                                    text = if (accepted.isNotEmpty()) {
                                        stringResource(R.string.lobby_start_ride_with_count, accepted.size + 1)
                                    } else {
                                        stringResource(R.string.lobby_start_ride)
                                    },
                                    enabled = true,
                                    onClick = {
                                        if (MandatoryLocationGate.evaluate(context) != LocationGateStatus.READY) {
                                            refreshLocationStatus()
                                            pendingStartRideAllowSolo = accepted.isEmpty()
                                            showLocationGateDialog = true
                                        } else if (accepted.isEmpty()) {
                                            showStartAloneDialog = true
                                        } else {
                                            viewModel.startRide(convoyName, allowSolo = false, onStartSuccess = {
                                                onStartRide(true)
                                            })
                                        }
                                    }
                                )
                            }

                            SecondaryButton(
                                text = stringResource(R.string.action_invite_friends),
                                onClick = {
                                    showAddRidersSheet = true
                                    onInviteMoreClick()
                                },
                                leadingIcon = RvIcons.PersonAdd
                            )
                        } else {
                            Surface(
                                color = MossContainer,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, Moss.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(RvSpacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = Moss,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(RvSpacing.md))
                                    Text(
                                        text = stringResource(R.string.lobby_waiting_host),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Moss,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            SecondaryButton(
                                text = stringResource(R.string.action_leave),
                                onClick = { showExitDialog = true },
                                destructive = true
                            )
                        }
                    }
                }
            }
        },
        containerColor = Paper
    ) { innerPadding ->
        if (status == null && activeRide == null) {
            val lobbyInitialLoadState: UiLoadState<Unit> = if (loadState is LobbyLoadState.Failed) {
                val failed = loadState as LobbyLoadState.Failed
                val errMsg = if (failed.isSchemaDegraded) {
                    stringResource(R.string.lobby_schema_degraded)
                } else {
                    failed.reason.ifBlank { stringResource(R.string.lobby_failed_desc) }
                }
                UiLoadState.Error(
                    message = errMsg,
                    onRetry = if (failed.isRetryable) ({ viewModel.retryPolling(convoyName) }) else null
                )
            } else {
                UiLoadState.Loading
            }

            RvStateHost(
                state = lobbyInitialLoadState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                loadingContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(RvSpacing.lg)
                            .testTag("rv_state_loading"),
                        verticalArrangement = Arrangement.spacedBy(RvSpacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LoadingSkeleton(height = 84.dp)
                        LoadingSkeleton(height = 84.dp)
                        Spacer(modifier = Modifier.height(RvSpacing.sm))
                        val loadingText = if (loadState is LobbyLoadState.Retrying) {
                            val retrying = loadState as LobbyLoadState.Retrying
                            stringResource(R.string.lobby_retrying, retrying.attempt, retrying.maxAttempts)
                        } else {
                            stringResource(R.string.lobby_loading)
                        }
                        Text(
                            text = loadingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                        TextButton(onClick = { showExitDialog = true }) {
                            Text(
                                text = stringResource(R.string.action_leave),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                errorContent = { err ->
                    val failed = loadState as? LobbyLoadState.Failed
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(RvSpacing.lg)
                            .testTag("rv_state_error"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            InlineError(
                                message = err.message,
                                onRetry = err.onRetry
                            )
                            if (!failed?.requestId.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(RvSpacing.xs))
                                Text(
                                    text = stringResource(R.string.lobby_request_id, failed!!.requestId!!),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(RvSpacing.md))
                            SecondaryButton(
                                text = stringResource(R.string.action_leave),
                                onClick = { showExitDialog = true },
                                destructive = true
                            )
                        }
                    }
                }
            ) {}
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = RvSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(RvSpacing.md),
                contentPadding = PaddingValues(vertical = RvSpacing.md)
            ) {
                // Failure banner if polling failed after having data / shell
                if (loadState is LobbyLoadState.Failed) {
                    val failed = loadState as LobbyLoadState.Failed
                    item {
                        Surface(
                            color = PaperRaised,
                            shape = RoundedCornerShape(RvRadius.md),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Brick),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(RvSpacing.md),
                                verticalArrangement = Arrangement.spacedBy(RvSpacing.sm)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = RvIcons.Warning,
                                        contentDescription = null,
                                        tint = Brick,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                                    Text(
                                        text = stringResource(R.string.lobby_failed_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Brick,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = if (failed.isSchemaDegraded) {
                                        stringResource(R.string.lobby_schema_degraded)
                                    } else {
                                        failed.reason.ifBlank { stringResource(R.string.lobby_failed_desc) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                                if (!failed.requestId.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(R.string.lobby_request_id, failed.requestId),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkSecondary
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
                                ) {
                                    if (failed.isRetryable) {
                                        Button(
                                            onClick = { viewModel.retryPolling(convoyName) },
                                            modifier = Modifier.weight(1f).height(RvTouchTarget.min),
                                            shape = RoundedCornerShape(RvRadius.sm),
                                            colors = ButtonDefaults.buttonColors(containerColor = Pop)
                                        ) {
                                            Text(stringResource(R.string.action_retry), color = PaperOnPop)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { showExitDialog = true },
                                        modifier = Modifier.weight(1f).height(RvTouchTarget.min),
                                        shape = RoundedCornerShape(RvRadius.sm),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brick)
                                    ) {
                                        Text(stringResource(R.string.action_leave))
                                    }
                                }
                            }
                        }
                    }
                } else if (status == null) {
                    // Shell initial connecting banner
                    item {
                        Surface(
                            color = PaperSunken,
                            shape = RoundedCornerShape(RvRadius.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = StatusWarn,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(RvSpacing.sm))
                                Text(
                                    text = if (loadState is LobbyLoadState.Retrying) {
                                        val retrying = loadState as LobbyLoadState.Retrying
                                        stringResource(R.string.lobby_retrying, retrying.attempt, retrying.maxAttempts)
                                    } else {
                                        stringResource(R.string.lobby_connecting)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarn
                                )
                            }
                        }
                    }
                } else if (error != null || loadState is LobbyLoadState.Retrying) {
                    // Polling / Reconnecting banner
                    item {
                        Surface(
                            color = PaperSunken,
                            shape = RoundedCornerShape(RvRadius.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = StatusWarn,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(RvSpacing.sm))
                                Text(
                                    text = if (loadState is LobbyLoadState.Retrying) {
                                        val retrying = loadState as LobbyLoadState.Retrying
                                        stringResource(R.string.lobby_retrying, retrying.attempt, retrying.maxAttempts)
                                    } else {
                                        stringResource(R.string.ride_status_reconnecting)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarn
                                )
                            }
                        }
                    }
                }

                // Ride Code Card (CodeCard)
                if (effectiveJoinCode.isNotBlank()) {
                    item {
                        CodeCard(
                            code = effectiveJoinCode,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText(context.getString(R.string.lobby_ride_code_label), effectiveJoinCode)
                                clipboard?.setPrimaryClip(clip)
                                feedback.showSuccess(context.getString(R.string.toast_ride_code_copied))
                            },
                            onShare = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_ride_code_text, effectiveDisplayName, effectiveJoinCode))
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share_ride_code_chooser)))
                            }
                        )
                    }
                }

                item {
                    val currentMeshConfig = activeRide?.meshConfig
                    val isConfiguring = currentMeshConfig?.offlineMeshEnabled == true && !currentMeshConfig.configCompleted
                    OfflineConfigCard(
                        isHost = isHost,
                        meshConfig = currentMeshConfig,
                        offlineBackupCode = status?.offlineBackupCode,
                        onToggleOffline = { viewModel.toggleOfflineMesh(it) },
                        onToggleAutoFallback = { viewModel.toggleAutomaticFallback(it) },
                        onToggleWarmStandby = { viewModel.toggleWarmStandby(it) },
                        isConfiguring = isConfiguring,
                        onOptOut = { viewModel.optOutOfOfflineMesh() },
                        onOptIn = { viewModel.optInToOfflineMesh() },
                        onRetryStep = { viewModel.retryOfflineStep(it) },
                        wifiDirectStatus = wifiDirectStatus,
                        selectedGoId = selectedGoId,
                        wifiAwareStatus = wifiAwareStatus,
                        wifiAwarePeerCount = wifiAwarePeerCount,
                        activeOfflineTransportMode = activeOfflineTransportMode
                    )
                }

                val routeOrigin = status?.origin ?: activeRide?.route?.originName
                val routeDest = status?.destination ?: activeRide?.route?.destinationName
                val routeMeetup = status?.meetupPoint ?: activeRide?.route?.meetupPoint
                val routeDurationMin = status?.estimatedDurationMin ?: activeRide?.route?.estimatedDurationMin
                if (locationStatus != LocationGateStatus.READY) {
                    item {
                        MandatoryLocationRequiredBanner(
                            status = locationStatus,
                            onActionClick = { showLocationGateDialog = true }
                        )
                    }
                }
                if (!routeOrigin.isNullOrBlank() || !routeDest.isNullOrBlank() || !routeMeetup.isNullOrBlank()) {
                    item {
                        LobbyRouteCard(
                            origin = routeOrigin,
                            destination = routeDest,
                            meetupPoint = routeMeetup,
                            durationMin = routeDurationMin,
                            onViewRouteMapClick = { showLobbyMap = true }
                        )
                    }
                }

                // Section 1: Joined (n)
                item {
                    Text(
                        text = stringResource(R.string.lobby_section_joined_count, accepted.size + 1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Host row
                item {
                    RiderRow(
                        name = stringResource(R.string.participant_you),
                        subtitle = if (isHost) stringResource(R.string.lobby_ride_host_role) else stringResource(R.string.lobby_section_joined),
                        status = if (isHost) "HOST" else "JOINED"
                    )
                }

                if (accepted.isEmpty() && !isHost) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rv_state_empty")
                        ) {
                            EmptyState(
                                title = stringResource(R.string.lobby_waiting_to_start),
                                body = stringResource(R.string.lobby_empty_joined),
                                icon = RvIcons.Groups
                            )
                        }
                    }
                } else {
                    items(accepted, key = { "accepted_${it.invitee.id}" }) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: stringResource(R.string.participant_rider_prefix)
                        val presenceText = if (entry.isPresent) stringResource(R.string.lobby_presence_active) else stringResource(R.string.lobby_presence_away)
                        val configText = when (entry.offlineConfigStatus) {
                            "READY" -> stringResource(R.string.lobby_offline_status_ready)
                            "CONFIGURING" -> stringResource(R.string.lobby_config_configuring)
                            "OPTED_OUT" -> stringResource(R.string.lobby_config_offline_off)
                            "TIMED_OUT" -> stringResource(R.string.lobby_offline_status_timed_out)
                            "UNAVAILABLE", "UNAVAILABLE_DEVICE" -> stringResource(R.string.lobby_offline_unavailable_device)
                            "FAILED" -> stringResource(R.string.lobby_config_setup_failed)
                            else -> stringResource(R.string.lobby_section_joined)
                        }
                        val statusText = if (entry.isPresent && entry.offlineConfigStatus == "READY") {
                            "READY"
                        } else {
                            "$presenceText • $configText"
                        }
                        RiderRow(
                            name = riderName,
                            subtitle = entry.invitee.bikeModel,
                            status = statusText,
                            trailing = if (isHost) {
                                {
                                    IconButton(onClick = { riderToRemove = entry.invitee }) {
                                        Icon(
                                            imageVector = RvIcons.RemoveCircleOutline,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = Brick
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
                            text = stringResource(R.string.lobby_section_invited_count, pending.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = InkSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(pending, key = { "pending_${it.invitee.id}" }) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: stringResource(R.string.participant_rider_prefix)
                        RiderRow(
                            name = riderName,
                            subtitle = entry.invitee.bikeModel,
                            status = "INVITED",
                            trailing = if (isHost) {
                                {
                                    IconButton(onClick = { riderToRemove = entry.invitee }) {
                                        Icon(
                                            imageVector = RvIcons.Close,
                                            contentDescription = stringResource(R.string.cd_cancel_invite),
                                            tint = InkSecondary
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
                            text = stringResource(R.string.lobby_section_declined_count, declined.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = InkSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(declined, key = { "declined_${it.invitee.id}" }) { entry ->
                        val riderName = entry.invitee.displayName ?: entry.invitee.handle ?: stringResource(R.string.participant_rider_prefix)
                        RiderRow(
                            name = riderName,
                            status = "DECLINED"
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(RvSpacing.xl))
                }
            }
        }
    }

    if (showLobbyMap) {
        ConvoyPreStartMapOverlay(
            roomName = currentRoomRef,
            onDismiss = { showLobbyMap = false }
        )
    }
    }
}

@Composable
private fun LobbyRouteCard(
    origin: String?,
    destination: String?,
    meetupPoint: String?,
    durationMin: Int?,
    onViewRouteMapClick: () -> Unit = {}
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(RvRadius.md),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lobby_route_card")
    ) {
        Column(
            modifier = Modifier.padding(RvSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RvSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                ) {
                    Icon(
                        imageVector = RvIcons.Map,
                        contentDescription = null,
                        tint = Pop,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.lobby_route_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                }
                if (durationMin != null && durationMin > 0) {
                    Text(
                        text = stringResource(R.string.lobby_route_eta_min, durationMin),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Moss
                    )
                }
            }
            if (!origin.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
                ) {
                    Icon(
                        imageVector = RvIcons.MyLocation,
                        contentDescription = null,
                        tint = Moss,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = origin,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (!destination.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
                ) {
                    Icon(
                        imageVector = RvIcons.LocationOn,
                        contentDescription = null,
                        tint = Brick,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = destination,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (!meetupPoint.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.lobby_route_meetup_prefix, meetupPoint),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(RvSpacing.xs))
            SecondaryButton(
                text = stringResource(R.string.lobby_view_meetup_map_cta),
                onClick = onViewRouteMapClick,
                leadingIcon = RvIcons.Map,
                modifier = Modifier.testTag("lobby_open_route_map_btn")
            )
        }
    }
}

@Composable
fun OfflineConfigCard(
    isHost: Boolean,
    meshConfig: RoomMeshConfig?,
    offlineBackupCode: String?,
    onToggleOffline: (Boolean) -> Unit,
    onToggleAutoFallback: (Boolean) -> Unit,
    onToggleWarmStandby: (Boolean) -> Unit,
    isConfiguring: Boolean,
    onOptOut: () -> Unit = {},
    onOptIn: () -> Unit = {},
    onRetryStep: (com.ridervoice.mesh.offline.OfflineConfigStep) -> Unit = {},
    wifiDirectStatus: WifiDirectStatus = WifiDirectStatus.IDLE,
    selectedGoId: String? = null,
    wifiAwareStatus: WifiAwareStatus = WifiAwareStatus.IDLE,
    wifiAwarePeerCount: Int = 0,
    activeOfflineTransportMode: ActiveOfflineTransportMode = ActiveOfflineTransportMode.MANUAL_OR_NONE
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PaperRaised),
        border = BorderStroke(1.dp, InkHairline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.mesh_title),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Only host can toggle room-level offline switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = meshConfig?.offlineMeshEnabled == true,
                        enabled = isHost,
                        role = Role.Switch,
                        onValueChange = { onToggleOffline(it) }
                    )
            ) {
                Text(
                    stringResource(R.string.lobby_offline_host_switch_label),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = meshConfig?.offlineMeshEnabled == true,
                    onCheckedChange = null,
                    enabled = isHost,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PaperOnPop,
                        checkedTrackColor = Pop,
                        uncheckedThumbColor = InkSecondary,
                        uncheckedTrackColor = PaperSunken
                    )
                )
            }

            if (meshConfig?.offlineMeshEnabled == true) {
                Spacer(modifier = Modifier.height(8.dp))
                // Per-step progress rows and status
                val steps = listOf(
                    com.ridervoice.mesh.offline.OfflineConfigStep.DEVICE_CAPABILITY to R.string.lobby_offline_step_device_capability,
                    com.ridervoice.mesh.offline.OfflineConfigStep.PERMISSIONS to R.string.lobby_offline_step_permissions,
                    com.ridervoice.mesh.offline.OfflineConfigStep.BATTERY_OPTIMIZATION to R.string.lobby_offline_step_battery,
                    com.ridervoice.mesh.offline.OfflineConfigStep.CREDENTIALS to R.string.lobby_offline_step_credentials,
                    com.ridervoice.mesh.offline.OfflineConfigStep.TRANSPORT_READY to R.string.lobby_offline_step_transport
                )

                for ((step, labelRes) in steps) {
                    val stepRes = meshConfig.stepResults[step]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        when (stepRes?.state) {
                            com.ridervoice.mesh.offline.StepState.PASSED -> {
                                Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            com.ridervoice.mesh.offline.StepState.WARNING -> {
                                Text("⚠", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                            }
                            com.ridervoice.mesh.offline.StepState.FAILED -> {
                                TextButton(
                                    onClick = { onRetryStep(step) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.lobby_offline_retry_step), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            com.ridervoice.mesh.offline.StepState.CHECKING -> {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            }
                            else -> {
                                Text("—", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Automatic Wi-Fi Aware chain / Wi-Fi Direct / hotspot status & convoy switching trade-off disclosure (v7 Section 13.1)
                Spacer(modifier = Modifier.height(4.dp))
                val linkLevelRes = when (meshConfig.linkLevel) {
                    RiderLinkLevel.AUTO_AWARE_CHAIN -> R.string.mesh_link_level_auto_aware_chain
                    RiderLinkLevel.AUTO_P2P -> R.string.mesh_link_level_auto_p2p
                    RiderLinkLevel.MANUAL_HOTSPOT -> R.string.mesh_link_level_manual_hotspot
                    RiderLinkLevel.INELIGIBLE -> R.string.mesh_convoy_unavailable
                }
                val linkLevelLabel = stringResource(R.string.mesh_diag_link_level)
                val linkLevelValue = stringResource(linkLevelRes)
                val linkLevelSummary = "$linkLevelLabel: $linkLevelValue"
                Text(
                    text = linkLevelSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meshConfig.linkLevel.supportsAutoLink) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                val awareCapText = stringResource(
                    if (meshConfig.wifiAwareSupported) R.string.mesh_aware_cap_supported
                    else R.string.mesh_aware_cap_unsupported
                )
                Text(
                    text = awareCapText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meshConfig.wifiAwareSupported) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (meshConfig.linkLevel == RiderLinkLevel.MANUAL_HOTSPOT) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.mesh_convoy_p2p_unsupported, 10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!selectedGoId.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val selectedGoLabel = stringResource(R.string.mesh_diag_selected_go)
                    val selectedGoSummary = "$selectedGoLabel: $selectedGoId"
                    Text(
                        text = selectedGoSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.mesh_convoy_lobby_tradeoff_disclosure),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                val (statusText, statusColor) = when (activeOfflineTransportMode) {
                    ActiveOfflineTransportMode.WIFI_AWARE_CHAIN -> when (wifiAwareStatus) {
                        WifiAwareStatus.ACTIVE_CHAIN -> stringResource(R.string.mesh_aware_status_ready, wifiAwarePeerCount) to MaterialTheme.colorScheme.primary
                        WifiAwareStatus.DISABLED_WIFI_OFF -> stringResource(R.string.lobby_p2p_status_wifi_off) to Brick
                        WifiAwareStatus.MISSING_PERMISSION -> stringResource(R.string.lobby_p2p_status_permission) to Brick
                        else -> stringResource(R.string.mesh_aware_status_forming) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ActiveOfflineTransportMode.WIFI_DIRECT_STAR -> when (wifiDirectStatus) {
                        WifiDirectStatus.CONNECTED -> stringResource(R.string.lobby_p2p_status_ready) to MaterialTheme.colorScheme.primary
                        WifiDirectStatus.CREATING_GROUP, WifiDirectStatus.CONNECTING -> stringResource(R.string.lobby_p2p_status_forming) to MaterialTheme.colorScheme.onSurfaceVariant
                        WifiDirectStatus.DISABLED_WIFI_OFF -> stringResource(R.string.lobby_p2p_status_wifi_off) to Brick
                        WifiDirectStatus.MISSING_PERMISSION -> stringResource(R.string.lobby_p2p_status_permission) to Brick
                        else -> stringResource(R.string.mesh_aware_status_fallback_p2p) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ActiveOfflineTransportMode.DISABLED_WIFI_OFF -> stringResource(R.string.lobby_p2p_status_wifi_off) to Brick
                    ActiveOfflineTransportMode.MANUAL_OR_NONE -> when (wifiDirectStatus) {
                        WifiDirectStatus.CONNECTED -> stringResource(R.string.lobby_p2p_status_ready) to MaterialTheme.colorScheme.primary
                        WifiDirectStatus.CREATING_GROUP, WifiDirectStatus.CONNECTING -> stringResource(R.string.lobby_p2p_status_forming) to MaterialTheme.colorScheme.onSurfaceVariant
                        WifiDirectStatus.DISABLED_WIFI_OFF -> stringResource(R.string.lobby_p2p_status_wifi_off) to Brick
                        WifiDirectStatus.MISSING_PERMISSION -> stringResource(R.string.lobby_p2p_status_permission) to Brick
                        else -> stringResource(R.string.lobby_offline_hotspot_hint) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )

                // Rider opt out / in control
                Spacer(modifier = Modifier.height(8.dp))
                if (meshConfig.optOut) {
                    Text(
                        text = stringResource(R.string.mesh_convoy_non_mesh_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedButton(
                        onClick = onOptIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.lobby_offline_opt_in_button))
                    }
                } else {
                    OutlinedButton(
                        onClick = onOptOut,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.lobby_offline_opt_out_button))
                    }
                }
            }

            if (meshConfig?.status == com.ridervoice.mesh.offline.OfflineConfigStatus.UNAVAILABLE_DEVICE) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.lobby_offline_unavailable_device),
                    color = Brick,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (meshConfig?.configCompleted == true && meshConfig.offlineMeshEnabled && !meshConfig.optOut) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.lobby_offline_status_ready),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.services.VoiceForegroundService
import com.ridervoice.state.RoomViewModel
import com.ridervoice.ui.screens.room.*
import com.ridervoice.ui.theme.*

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomName: String,
    userName: String,
    onLeave: () -> Unit,
    onSosClick: () -> Unit,
    viewModel: RoomViewModel = hiltViewModel(),
    homeViewModel: com.ridervoice.ui.viewmodels.HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme

    val connectionState by viewModel.connectionState.collectAsState()
    val participants by viewModel.participants.collectAsState()
    val activeSpeaker by viewModel.activeSpeaker.collectAsState()
    val remoteLocations by viewModel.remoteLocations.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val isVoxOpen by viewModel.isVoxOpen.collectAsState()
    val activeDevice by viewModel.activeAudioDevice.collectAsState()
    val routerState by viewModel.audioRouterState.collectAsState()
    val requestedDevice by viewModel.requestedAudioDevice.collectAsState()
    val routedInput by viewModel.routedInput.collectAsState()
    val routedOutput by viewModel.routedOutput.collectAsState()
    val audioStatusLine by viewModel.audioStatusLine.collectAsState()
    val error by viewModel.error.collectAsState()
    val inRideSosAlert by viewModel.inRideSosAlert.collectAsState()

    var isVoiceChannelExpanded by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    val isDeafened by viewModel.deafened.collectAsState()
    val convoyEnded by viewModel.convoyEnded.collectAsState()
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showAudioRoutePicker by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    val latestSelfMetric by viewModel.latestSelfMetric.collectAsState()
    val diagSampleCount by viewModel.diagSampleCount.collectAsState()
    val isCollectingDiag by viewModel.isCollectingDiagnostics.collectAsState()

    val activeRide by viewModel.activeRide.collectAsState()

    LaunchedEffect(Unit) {
        if (activeRide?.diagnosticsEnabled == true && !viewModel.isDiagnosticsConsentShown()) {
            showConsentDialog = true
        }
    }

    val isHost = activeRide?.isHost ?: false
    val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val otherRiders = remember(participants, currentUid) {
        participants.filter { !it.isGhost && it.identity != currentUid }
    }

    fun exitRideCleanup() {
        showLeaveConfirmDialog = false
        homeViewModel.clearActiveRide()
        context.stopService(Intent(context, VoiceForegroundService::class.java))
        onLeave()
    }

    LaunchedEffect(convoyEnded) {
        if (convoyEnded) {
            exitRideCleanup()
        }
    }

    BackHandler {
        showLeaveConfirmDialog = true
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun startVoiceServiceIfPermitted() {
        try {
            val hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasRecordAudio) {
                val serviceIntent = Intent(context, VoiceForegroundService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                android.util.Log.w("RoomScreen", "RECORD_AUDIO not granted; deferring VoiceForegroundService")
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomScreen", "Failed to start VoiceForegroundService", e)
        }
    }

    val ridePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (permissions[android.Manifest.permission.RECORD_AUDIO] == true) {
            startVoiceServiceIfPermitted()
        }
    }

    LaunchedEffect(Unit) {
        ridePermissionLauncher.launch(com.ridervoice.permissions.PermissionManager.ridePermissions)
    }

    LaunchedEffect(roomName, userName) {
        startVoiceServiceIfPermitted()
        viewModel.joinRoom(roomName, userName)
    }

    // Dialogs
    if (showLeaveConfirmDialog) {
        LeaveRideDialog(
            isHost = isHost,
            otherRiders = otherRiders,
            onDismiss = { showLeaveConfirmDialog = false },
            onLeaveAsParticipant = {
                viewModel.leaveRoom()
                exitRideCleanup()
            },
            onTransferAndLeave = { successorId ->
                viewModel.transferHostAndLeave(roomName, successorId) {
                    exitRideCleanup()
                }
            },
            onEndRideForEveryone = {
                viewModel.endRideForEveryone(roomName) {
                    exitRideCleanup()
                }
            }
        )
    }

    if (showAudioRoutePicker) {
        AudioRouteSheet(
            onSelectRoute = { route -> viewModel.setAudioRoute(route) },
            onDismiss = { showAudioRoutePicker = false }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            rideName = roomName,
            activeDevice = activeDevice,
            latestSelfMetric = latestSelfMetric,
            diagSampleCount = diagSampleCount,
            participants = participants,
            currentUid = currentUid,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }

    if (showConsentDialog) {
        DiagnosticsConsentDialog(
            onAccept = {
                viewModel.setDiagnosticsConsentChoice(true)
                showConsentDialog = false
            },
            onDecline = {
                viewModel.setDiagnosticsConsentChoice(false)
                showConsentDialog = false
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
            .imePadding(),
        topBar = {
            RideTopBar(
                rideName = roomName,
                connectionState = connectionState,
                activeSpeaker = activeSpeaker,
                currentUid = currentUid,
                participants = participants,
                activeDevice = activeDevice,
                routerState = routerState,
                requestedDevice = requestedDevice,
                routedInput = routedInput,
                routedOutput = routedOutput,
                isCollectingDiag = isCollectingDiag,
                diagSampleCount = diagSampleCount,
                onAudioRouteClick = { showAudioRoutePicker = true },
                onDiagnosticsClick = { showDiagnosticsDialog = true },
                onLeaveClick = { showLeaveConfirmDialog = true },
                onRescanAudio = { viewModel.rescanAudioDevices() }
            )
        },
        bottomBar = {
            Surface(
                color = DarkSlate,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Integrated VU Meter & Audio Status Line
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioVuMeter(
                            viewModel = viewModel,
                            isSpeaking = isMicEnabled || isVoxOpen,
                            modifier = Modifier.width(110.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = audioStatusLine,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Pinned Talk Controls (PTT, Mic toggle, Deafen toggle, SOS)
                    TalkControls(
                        isMicEnabled = isMicEnabled,
                        isDeafened = isDeafened,
                        isPttPressed = isPttPressed,
                        isVoxOpen = isVoxOpen,
                        onToggleMic = { viewModel.toggleMute() },
                        onToggleDeafen = { viewModel.setDeafened(!isDeafened) },
                        onPttPressStart = {
                            isPttPressed = true
                            viewModel.onPttPressed(true)
                        },
                        onPttPressEnd = {
                            isPttPressed = false
                            viewModel.onPttPressed(false)
                        },
                        onSosClick = onSosClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        containerColor = GraphiteBase
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Banners area: In-ride SOS alert, then error banner (stacked, dismissible)
            SosAlertBanner(
                alert = inRideSosAlert,
                onDismiss = { viewModel.dismissInRideSosAlert() }
            )

            error?.let { msg ->
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC331010))
                        .border(1.dp, AlertRed, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Participants list as the flexible, scrollable region
            RiderList(
                participants = participants,
                currentUid = currentUid,
                activeSpeaker = activeSpeaker,
                isCurrentUserTransmitting = isPttPressed || isMicEnabled || isVoxOpen,
                isHost = isHost,
                remoteLocations = remoteLocations,
                currentLocation = currentLocation,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 120.dp)
            )
        }
    }
}

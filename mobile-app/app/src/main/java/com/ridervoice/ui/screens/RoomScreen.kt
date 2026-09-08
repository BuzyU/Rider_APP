package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RouterState
import com.ridervoice.network.ConnectionState
import com.ridervoice.services.VoiceForegroundService
import com.ridervoice.state.RoomViewModel
import com.ridervoice.ui.components.ParticipantCard
import com.ridervoice.ui.theme.*

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
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val isVoxOpen by viewModel.isVoxOpen.collectAsState()
    val activeDevice by viewModel.activeAudioDevice.collectAsState()
    val routerState by viewModel.audioRouterState.collectAsState()
    val audioStatusLine by viewModel.audioStatusLine.collectAsState()
    val amplitude by viewModel.currentAmplitude.collectAsState()
    val noiseFloor by viewModel.noiseFloor.collectAsState()
    val error by viewModel.error.collectAsState()

    var isVoiceChannelExpanded by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var isDeafened by remember { mutableStateOf(false) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showAudioRoutePicker by remember { mutableStateOf(false) }

    val isHost = com.ridervoice.models.RideSession.isHost
    val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val otherRiders = remember(participants, currentUid) {
        participants.filter { !it.isGhost && it.identity != currentUid }
    }

    fun exitRideCleanup() {
        showLeaveConfirmDialog = false
        homeViewModel.clearActiveRide()
        com.ridervoice.models.RideSession.clear()
        context.stopService(Intent(context, VoiceForegroundService::class.java))
        onLeave()
    }

    // Intercept hardware/system back button with confirmation
    BackHandler {
        showLeaveConfirmDialog = true
    }

    // Point-of-use runtime permissions: Background location launcher (API 29+)
    val bgLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Point-of-use runtime permissions: Location + Notifications
    val ridePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    LaunchedEffect(Unit) {
        ridePermissionLauncher.launch(com.ridervoice.permissions.PermissionManager.ridePermissions)
    }

    // Start session
    LaunchedEffect(roomName, userName) {
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
        viewModel.joinRoom(roomName, userName)
    }

    // ── LEAVE CONFIRMATION DIALOG (HOST VS RIDER SEMANTICS) ───────────────────
    if (showLeaveConfirmDialog) {
        if (isHost) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmDialog = false },
                title = {
                    Text(
                        text = "LEADER DEPARTURE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (otherRiders.isNotEmpty()) {
                            Text(
                                text = "You are the convoy host with ${otherRiders.size} other rider(s) tuned in. You can end the ride for all members or transfer leadership to keep the convoy channel open.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            Text(
                                text = "You are currently the only rider in this convoy. Leaving will conclude and terminate the session.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (otherRiders.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val successor = otherRiders.first()
                                    viewModel.transferHostAndLeave(roomName, successor.identity) {
                                        exitRideCleanup()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "TRANSFER HOST & LEAVE",
                                    color = if (isDark) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.endRideForEveryone(roomName) {
                                    exitRideCleanup()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (otherRiders.isNotEmpty()) "END RIDE FOR EVERYONE" else "END RIDE",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { showLeaveConfirmDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("STAY IN CONVOY", color = TextPrimary)
                        }
                    }
                },
                dismissButton = {},
                containerColor = DarkSlate
            )
        } else {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmDialog = false },
                title = {
                    Text(
                        text = "LEAVE CONVOY VOICE?",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = "You will disconnect from active convoy communications and live background audio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.leaveRoom()
                            exitRideCleanup()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("LEAVE CONVOY", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showLeaveConfirmDialog = false },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("STAY IN CONVOY", color = TextPrimary)
                    }
                },
                containerColor = DarkSlate
            )
        }
    }

    // ── AUDIO ROUTE SELECTOR DIALOG ─────────────────────────────────────────
    if (showAudioRoutePicker) {
        AlertDialog(
            onDismissRequest = { showAudioRoutePicker = false },
            title = {
                Text(
                    text = "AUDIO OUTPUT ROUTE",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select audio hardware device:", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAudioRoutePicker = false
                            }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = ElectricCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Helmet Headset (Bluetooth SCO)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAudioRoutePicker = false
                            }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = NeonOrange)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Phone Speakerphone", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAudioRoutePicker = false
                            }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = TextSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Phone Earpiece / Wired", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioRoutePicker = false }) {
                    Text("CLOSE", color = ElectricCyan)
                }
            },
            containerColor = DarkSlate
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(GraphiteBase)) {

        // ── Background: navigation delegation placeholder ───────────────────
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("TACTICAL CONVOY ACTIVE", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("Voice and telemetry running", color = TextSecondary)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q="))
                    intent.setPackage("com.google.android.apps.maps")
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
            ) {
                Text("LAUNCH NAVIGATION", color = if (isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // ── Top HUD ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live / connection status
            ConnectionPill(connectionState)

            // Room info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = roomName.uppercase(),
                    color = NeonOrange,
                    style = MaterialTheme.typography.titleLarge,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${participants.count { !it.isGhost }} RIDERS CONNECTED",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Right side: Audio device badge + Separated LEAVE button
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.clickable { showAudioRoutePicker = true }) {
                    AudioDeviceBadge(activeDevice, routerState)
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Cleanly separated LEAVE button on top header (safe from SOS)
                IconButton(
                    onClick = { showLeaveConfirmDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Gunmetal)
                        .border(1.5.dp, AlertRed, CircleShape)
                        .semantics { contentDescription = "Leave Convoy Voice Call" }
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Leave Convoy",
                        tint = AlertRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Error banner (dismissible) ───────────────────────────────────────
        error?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC331010))
                    .border(1.dp, AlertRed, RoundedCornerShape(8.dp))
                    .padding(12.dp)
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

        // ── S-Meter / VU Audio Status Bar (below top HUD) ───────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (error != null) 175.dp else 115.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSlate)
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // S-Meter / VU Segmented Ladder
                    SegmentedVuMeter(
                        amplitude = amplitude,
                        noiseFloor = noiseFloor,
                        isTransmitting = isMicEnabled || isVoxOpen,
                        modifier = Modifier.width(130.dp).height(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = audioStatusLine.uppercase(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Bottom voice channel console ────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (isVoiceChannelExpanded) 0.75f else 0.32f)
                .background(DarkSlate, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isVoiceChannelExpanded = !isVoiceChannelExpanded }
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSecondary)
                )
            }

            if (isVoiceChannelExpanded) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TRANSCEIVER CHANNEL",
                            color = NeonOrange,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = roomName.uppercase(),
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    IconButton(onClick = { isVoiceChannelExpanded = false }) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Collapse Channel", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(participants) { participant ->
                        ParticipantCard(participant = participant, isFaded = participant.isGhost)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            } else {
                // Collapsed: active speaker preview
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Gunmetal)
                            .border(1.dp, BorderColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${participants.count { !it.isGhost }} RIDERS ON FREQUENCY",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isDeafened) "INCOMING AUDIO DEAFENED" else audioStatusLine,
                            color = if (isDeafened) AlertRed else TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = { isVoiceChannelExpanded = true }) {
                        Icon(Icons.Default.ExpandLess, contentDescription = "Expand Channel", tint = TextSecondary)
                    }
                }
            }

            // ── TACTICAL CONTROLS RACK ───────────────────────────────────────
            // Separated controls: Mute + Deafen on left, Perforated PTT in center, Guarded SOS on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. MUTE TOGGLE (Changed LIVE color off red to ElectricCyan / RF Teal)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    FilledIconButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier
                            .size(52.dp)
                            .border(
                                1.5.dp,
                                if (isMicEnabled) ElectricCyan else BorderColor,
                                CircleShape
                            ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isMicEnabled) DarkPalette.SurfaceAlt else Gunmetal
                        )
                    ) {
                        Icon(
                            imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (isMicEnabled) "Microphone is live, tap to mute" else "Microphone muted, tap to unmute",
                            tint = if (isMicEnabled) ElectricCyan else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isMicEnabled) "LIVE" else "MUTED",
                        color = if (isMicEnabled) ElectricCyan else TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // 2. DEAFEN TOGGLE (Silence incoming chatter)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    FilledIconButton(
                        onClick = { isDeafened = !isDeafened },
                        modifier = Modifier
                            .size(52.dp)
                            .border(
                                1.5.dp,
                                if (isDeafened) AlertRed else BorderColor,
                                CircleShape
                            ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isDeafened) DarkPalette.EmergencyContainer else Gunmetal
                        )
                    ) {
                        Icon(
                            imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isDeafened) "Incoming audio muted, tap to hear chatter" else "Tap to deafen incoming audio",
                            tint = if (isDeafened) AlertRed else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isDeafened) "DEAFEN" else "HEAR",
                        color = if (isDeafened) AlertRed else TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // 3. PTT CAPSULE BUTTON (Perforated Palm-Mic Grille)
                PttPerforatedButton(
                    isPressed = isPttPressed,
                    isVoxOpen = isVoxOpen,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp),
                    onPressStart = {
                        isPttPressed = true
                        viewModel.onPttPressed(true)
                    },
                    onPressEnd = {
                        isPttPressed = false
                        viewModel.onPttPressed(false)
                    }
                )

                // 4. GUARDED SOS SWITCH (Separated, unmistakable hazard chamber)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HazardContainer)
                            .border(2.dp, AlertRed, RoundedCornerShape(10.dp))
                            .clickable { onSosClick() }
                            .semantics { contentDescription = "Emergency SOS Hazard Switch" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "SOS",
                            tint = AlertRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "SOS",
                        color = AlertRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

// ── Retro S-Meter / VU Segmented Gauge ─────────────────────────────────────────

@Composable
private fun SegmentedVuMeter(
    amplitude: Float,
    noiseFloor: Float,
    isTransmitting: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = ThemeState.isDarkTheme
    val activeLevel = if (noiseFloor > 0 && isTransmitting) {
        ((amplitude / (noiseFloor * 4.5f)).coerceIn(0f, 1f) * 8).toInt()
    } else {
        0
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in 1..8) {
                val isLit = isTransmitting && i <= activeLevel
                val segmentColor = when {
                    !isLit -> if (isDark) Color(0xFF20252D) else LightPalette.SurfaceAlt
                    i <= 5 -> ElectricCyan
                    i <= 7 -> NeonOrange
                    else   -> AlertRed
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(segmentColor)
                        .border(0.5.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-20", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("-10", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("0", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("+3dB", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
        }
    }
}

// ── Retro Perforated Palm-Mic PTT Capsule ──────────────────────────────────────

@Composable
private fun PttPerforatedButton(
    isPressed: Boolean,
    isVoxOpen: Boolean,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme
    val isActive = isPressed || isVoxOpen

    val pulseTransition = rememberInfiniteTransition(label = "ptt")
    val scale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pttScale"
    )

    val buttonBg = when {
        isPressed -> if (isDark) AmberContainer else LightPalette.AccentAmber
        isVoxOpen -> if (isDark) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
        else      -> if (isDark) DarkPalette.SurfaceAlt else LightPalette.Surface
    }

    val rimColor = when {
        isPressed -> NeonOrange
        isVoxOpen -> ElectricCyan
        else      -> BorderColor
    }

    val dotColor = when {
        isPressed -> if (isDark) NeonOrange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.6f)
        else      -> if (isDark) Color(0xFF101316) else Color(0xFF5A626E).copy(alpha = 0.25f)
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(buttonBg)
            .border(2.dp, rimColor, RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    }
                )
            }
            .semantics { contentDescription = "Push To Talk Radio Capsule" },
        contentAlignment = Alignment.Center
    ) {
        // Canvas Perforated Mic Grille
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepX = 14.dp.toPx()
            val stepY = 10.dp.toPx()
            val dotRadius = 1.5.dp.toPx()
            val cols = (size.width / stepX).toInt()
            val rows = (size.height / stepY).toInt()

            for (col in 1..cols) {
                for (row in 1..rows) {
                    val x = col * stepX - (stepX / 2)
                    val y = row * stepY - (stepY / 2)
                    drawCircle(
                        color = dotColor,
                        radius = dotRadius,
                        center = Offset(x, y)
                    )
                }
            }
        }

        // Center Callout Plate
        Surface(
            color = if (isPressed) NeonOrange else Gunmetal.copy(alpha = 0.9f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, rimColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isPressed) (if (isDark) Color.Black else Color.White) else (if (isActive) ElectricCyan else TextPrimary),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isPressed -> "TRANSMITTING"
                        isVoxOpen -> "VOX ACTIVE"
                        else      -> "HOLD TO TALK"
                    },
                    color = if (isPressed) (if (isDark) Color.Black else Color.White) else TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun ConnectionPill(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> TechGreen    to "LOCKED"
        ConnectionState.CONNECTING   -> NeonOrange   to "ACQUIRING"
        ConnectionState.RECONNECTING -> NeonOrange   to "SYNCING"
        ConnectionState.FAILED       -> AlertRed     to "NO SIGNAL"
        ConnectionState.DISCONNECTED -> TextSecondary to "STANDBY"
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTED) 0.4f else 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun AudioDeviceBadge(device: AudioDevice, routerState: RouterState) {
    val (icon, label, color) = when {
        routerState == RouterState.CONNECTING_BT -> Triple(Icons.Default.Bluetooth, "BT SYNC…", NeonOrange)
        routerState == RouterState.SCANNING      -> Triple(Icons.Default.Search, "SEARCH…", TextSecondary)
        device is AudioDevice.BluetoothSco       -> Triple(Icons.Default.BluetoothConnected, device.deviceName.take(12).uppercase(), ElectricCyan)
        device is AudioDevice.WiredHeadset       -> Triple(Icons.Default.Headset, "WIRED", TechGreen)
        device is AudioDevice.UsbAudio           -> Triple(Icons.Default.Usb, "USB-RF", TechGreen)
        else                                     -> Triple(Icons.Default.PhoneInTalk, "EARPIECE", TextSecondary)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSlate)
            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = "Audio Route", tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

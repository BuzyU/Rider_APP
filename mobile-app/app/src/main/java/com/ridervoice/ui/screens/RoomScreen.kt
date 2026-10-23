package com.ridervoice.ui.screens

import android.content.Intent
import android.location.Location
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.ridervoice.audio.RoutedDeviceInfo
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

    val bgLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
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

    val ridePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
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
                                viewModel.setAudioRoute("BLUETOOTH")
                                showAudioRoutePicker = false
                            }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = ElectricCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bluetooth Headset / AirPods", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setAudioRoute("SPEAKER")
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
                                viewModel.setAudioRoute("WIRED")
                                showAudioRoutePicker = false
                            }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = TextSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Phone Earpiece / Wired Headset", color = TextPrimary, fontWeight = FontWeight.Bold)
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

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Diagnostics",
                        tint = ElectricCyan,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "CONVOY TELEMETRY & DIAGNOSTICS",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = roomName.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TechGreen.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(TechGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "MONITORING ACTIVE — $diagSampleCount SAMPLES LOGGED",
                                    color = TechGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Auto-exported to code4changeteam@gmail.com in multi-sheet Excel (.xlsx) on ride conclusion.",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "AUDIO TRANSCEIVER ROUTE",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeDevice.displayName(),
                                color = ElectricCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (activeDevice is AudioDevice.BluetoothSco) "Wideband HFP/SCO Active (AirPods/Headset)" else "Direct System Communication Route",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "LIVE NETWORK LINK QUALITY",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "${latestSelfMetric?.rttMs?.toInt() ?: "--"} ms",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text("ROUND-TRIP (RTT)", color = TextSecondary, fontSize = 9.sp)
                                }
                                Column {
                                    Text(
                                        text = "${latestSelfMetric?.jitterMs?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--"} ms",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text("JITTER", color = TextSecondary, fontSize = 9.sp)
                                }
                                Column {
                                    Text(
                                        text = "${latestSelfMetric?.packetsLost ?: 0}",
                                        color = if ((latestSelfMetric?.packetsLost ?: 0) > 0) AlertRed else TechGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text("PKTS LOST", color = TextSecondary, fontSize = 9.sp)
                                }
                            }
                        }
                    }

                    Surface(
                        color = Gunmetal,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "DEVICE HARDWARE HEALTH",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Carrier: ${latestSelfMetric?.networkType ?: "CONNECTED"}",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Battery: ${latestSelfMetric?.batteryLevel?.toInt() ?: "--"}%",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Thermals: ${latestSelfMetric?.thermalStatus ?: "NORMAL"}",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Text(
                        text = "MONITORED RIDERS IN CONVOY (${participants.size})",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    participants.forEach { rider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Gunmetal)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (rider.identity == currentUid) "YOU (${rider.displayName ?: "Local"})" else (rider.displayName ?: rider.identity.take(8)),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (rider.isGhost) "RECONNECTING" else "STREAMING",
                                color = if (rider.isGhost) NeonOrange else TechGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("CLOSE", color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSlate
        )
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { /* modal: require choice */ },
            title = {
                Text(
                    text = "SHARE CONVOY DIAGNOSTICS?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "The host has enabled diagnostics for this convoy. Only network link metrics and device performance telemetry are collected (zero audio content, zero GPS location). Data is automatically compiled and emailed to the project team upon convoy conclusion to troubleshoot call drops.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setDiagnosticsConsentChoice(true)
                        showConsentDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TechGreen)
                ) {
                    Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.setDiagnosticsConsentChoice(false)
                        showConsentDialog = false
                    }
                ) {
                    Text("Don't share my data", color = AlertRed)
                }
            },
            containerColor = DarkSlate
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(GraphiteBase)) {

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(connectionState)
                val isCollectingDiag by viewModel.isCollectingDiagnostics.collectAsState()
                if (isCollectingDiag) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x33FF3B30))
                            .border(1.dp, Color(0xFFFF3B30), RoundedCornerShape(4.dp))
                            .clickable { showDiagnosticsDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF3B30))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (diagSampleCount > 0) "DIAG ($diagSampleCount)" else "REC",
                            color = Color(0xFFFF3B30),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = roomName.uppercase(),
                    color = NeonOrange,
                    style = MaterialTheme.typography.titleLarge,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (activeSpeaker != null) {
                        val spk = participants.find { it.identity == activeSpeaker }
                        val name = if (activeSpeaker == currentUid) "YOU" else (spk?.displayName ?: activeSpeaker ?: "UNKNOWN")
                        "TRANSMITTING: $name"
                    } else {
                        "${participants.count { !it.isGhost }} RIDERS CONNECTED"
                    },
                    color = if (activeSpeaker != null) ElectricCyan else TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (activeSpeaker != null) FontWeight.Bold else FontWeight.Normal
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.clickable { showAudioRoutePicker = true }) {
                    AudioDeviceBadge(
                        device = activeDevice,
                        routerState = routerState,
                        requestedDevice = requestedDevice,
                        routedInput = routedInput,
                        routedOutput = routedOutput,
                        onRetry = { viewModel.rescanAudioDevices() }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

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

                    SegmentedVuMeter(
                        viewModel = viewModel,
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (isVoiceChannelExpanded) 0.75f else 0.32f)
                .background(DarkSlate, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {

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
                    items(
                        items = participants.filter { !it.isGhost },
                        key = { it.identity }
                    ) { participant ->
                        val isCurrentUser = (participant.identity == currentUid)
                        val isSpeaking = (activeSpeaker != null && activeSpeaker == participant.identity) || (isCurrentUser && (isPttPressed || isMicEnabled || isVoxOpen))
                        val isParticipantHost = (isHost && isCurrentUser)
                        val rLoc = remoteLocations[participant.identity]
                        val distanceMeters = remember(currentLocation?.latitude, currentLocation?.longitude, rLoc?.lat, rLoc?.lng, isCurrentUser) {
                            if (isCurrentUser || rLoc == null || currentLocation == null) null else {
                                val results = FloatArray(1)
                                Location.distanceBetween(currentLocation!!.latitude, currentLocation!!.longitude, rLoc.lat, rLoc.lng, results)
                                results[0]
                            }
                        }
                        val speedKmh = if (isCurrentUser) {
                            currentLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
                        } else {
                            rLoc?.speed?.times(3.6f)
                        }
                        ParticipantCard(
                            participant = participant,
                            isFaded = participant.isGhost,
                            isSpeaking = isSpeaking,
                            isCurrentUser = isCurrentUser,
                            isHost = isParticipantHost,
                            distanceMeters = distanceMeters,
                            speedKmh = speedKmh
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            } else {

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
                            text = when {
                                isDeafened -> "INCOMING AUDIO DEAFENED"
                                activeSpeaker != null -> {
                                    val spk = participants.find { it.identity == activeSpeaker }
                                    val name = if (activeSpeaker == currentUid) "YOU" else (spk?.displayName ?: activeSpeaker ?: "UNKNOWN")
                                    "ACTIVE: $name"
                                }
                                else -> audioStatusLine
                            },
                            color = when {
                                isDeafened -> AlertRed
                                activeSpeaker != null -> ElectricCyan
                                else -> TextSecondary
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeSpeaker != null) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    IconButton(onClick = { isVoiceChannelExpanded = true }) {
                        Icon(Icons.Default.ExpandLess, contentDescription = "Expand Channel", tint = TextSecondary)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

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

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    FilledIconButton(
                        onClick = { viewModel.setDeafened(!isDeafened) },
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

@Composable
private fun SegmentedVuMeter(
    viewModel: RoomViewModel,
    isTransmitting: Boolean,
    modifier: Modifier = Modifier
) {
    val amplitude by viewModel.currentAmplitude.collectAsState()
    val noiseFloor by viewModel.noiseFloor.collectAsState()
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
private fun AudioDeviceBadge(
    device: AudioDevice,
    routerState: RouterState,
    requestedDevice: AudioDevice?,
    routedInput: RoutedDeviceInfo,
    routedOutput: RoutedDeviceInfo,
    onRetry: () -> Unit
) {
    val isMismatch = requestedDevice != null &&
        (requestedDevice is AudioDevice.BluetoothSco || requestedDevice is AudioDevice.WiredHeadset || requestedDevice is AudioDevice.UsbAudio) &&
        (device !is AudioDevice.BluetoothSco && device !is AudioDevice.WiredHeadset && device !is AudioDevice.UsbAudio)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSlate)
            .border(1.dp, if (isMismatch) AlertRed else BorderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (isMismatch) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Using phone audio, headset not active",
                    color = AlertRed,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Text("Retry", fontSize = 8.sp, color = Color.White)
                }
            }
        }
        Text(
            text = "Mic: ${routedInput.display()}",
            color = if (isMismatch) AlertRed else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
        )
        Text(
            text = "Speaker: ${routedOutput.display()}",
            color = if (isMismatch) AlertRed else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
        )
    }
}

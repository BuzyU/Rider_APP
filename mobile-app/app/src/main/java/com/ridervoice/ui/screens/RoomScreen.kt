package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
    viewModel: RoomViewModel = hiltViewModel()
) {
    val context = LocalContext.current

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
        val serviceIntent = Intent(context, VoiceForegroundService::class.java)
        context.startForegroundService(serviceIntent)
        viewModel.joinRoom(roomName, userName)
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
                Text("LAUNCH NAVIGATION", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        // ── Top HUD ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Live / connection status
            ConnectionPill(connectionState)

            // Room info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(roomName, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text("${participants.count { !it.isGhost }} connected", color = TextSecondary, fontSize = 12.sp)
            }

            // Audio device badge
            AudioDeviceBadge(activeDevice, routerState)
        }

        // ── Error banner ───────────────────────────────────────────────────
        error?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x88FF4D4D))
                    .padding(12.dp)
            ) {
                Text(msg, color = TextPrimary, fontSize = 13.sp)
            }
        }

        // ── Audio status bar (below top HUD) ───────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = if (error != null) 190.dp else 130.dp) // BUG FIX: Move down if error banner is showing
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSlate)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Amplitude bar
                AmplitudeBar(
                    amplitude = amplitude,
                    noiseFloor = noiseFloor,
                    isTransmitting = isMicEnabled || isVoxOpen,
                    modifier = Modifier.width(80.dp).height(14.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(audioStatusLine, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }

        // ── Bottom voice channel panel ──────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (isVoiceChannelExpanded) 0.72f else 0.28f)
                .background(DarkSlate, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary))
            }

            if (isVoiceChannelExpanded) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("VOICE CHANNEL", color = TextSecondary, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Text(roomName, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { isVoiceChannelExpanded = false }) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Collapse", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(participants) { participant ->
                        ParticipantCard(participant = participant, isFaded = participant.isGhost)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            } else {
                // Collapsed: active speaker row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Gunmetal),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${participants.count { !it.isGhost }} riders online", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(audioStatusLine, color = TextSecondary, fontSize = 11.sp)
                    }
                    IconButton(onClick = { isVoiceChannelExpanded = true }) {
                        Icon(Icons.Default.ExpandLess, contentDescription = "Expand", tint = TextSecondary)
                    }
                }
            }

            // ── Controls row ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute toggle (VOX override)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { viewModel.toggleMute() })
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isMicEnabled) Color(0x44FF4D4D) else Gunmetal)
                            .border(1.dp, if (isMicEnabled) AlertRed else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Mute",
                            tint = if (isMicEnabled) AlertRed else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (isMicEnabled) "LIVE" else "MUTED", color = if (isMicEnabled) AlertRed else TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                // ── PTT BUTTON ─────────────────────────────────────────────
                // Press and hold to transmit. Releases mic on lift.
                PttButton(
                    isPressed = isPttPressed,
                    isVoxOpen = isVoxOpen,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).fillMaxHeight(),
                    onPressStart = {
                        isPttPressed = true
                        viewModel.onPttPressed(true)
                    },
                    onPressEnd = {
                        isPttPressed = false
                        viewModel.onPttPressed(false)
                    }
                )

                // SOS
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onSosClick() })
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF4D4D))
                            .border(1.dp, AlertRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "SOS", tint = AlertRed, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SOS", color = AlertRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Leave
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            viewModel.leaveRoom()
                            context.stopService(Intent(context, VoiceForegroundService::class.java))
                            onLeave()
                        })
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .border(2.dp, AlertRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Leave", tint = AlertRed, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("LEAVE", color = AlertRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun ConnectionPill(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> SuccessGreen to "LIVE"
        ConnectionState.CONNECTING   -> NeonOrange   to "CONNECTING"
        ConnectionState.RECONNECTING -> NeonOrange   to "RECONNECTING"
        ConnectionState.FAILED       -> AlertRed     to "FAILED"
        ConnectionState.DISCONNECTED -> TextSecondary to "OFFLINE"
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (state == ConnectionState.CONNECTED) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun AudioDeviceBadge(device: AudioDevice, routerState: RouterState) {
    val (icon, label, color) = when {
        routerState == RouterState.CONNECTING_BT -> Triple(Icons.Default.Bluetooth, "Connecting…", NeonOrange)
        routerState == RouterState.SCANNING -> Triple(Icons.Default.Search, "Scanning…", TextSecondary)
        device is AudioDevice.BluetoothSco -> Triple(Icons.Default.BluetoothConnected, device.deviceName.take(14), ElectricCyan)
        device is AudioDevice.WiredHeadset -> Triple(Icons.Default.Headset, "Wired", SuccessGreen)
        device is AudioDevice.UsbAudio -> Triple(Icons.Default.Usb, "USB", SuccessGreen)
        else -> Triple(Icons.Default.PhoneInTalk, "Earpiece", TextSecondary)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AmplitudeBar(
    amplitude: Float,
    noiseFloor: Float,
    isTransmitting: Boolean,
    modifier: Modifier = Modifier
) {
    val barColor = when {
        isTransmitting && amplitude > noiseFloor * 2.5f -> SuccessGreen
        isTransmitting -> ElectricCyan
        else -> Gunmetal
    }

    val fillFraction = if (noiseFloor > 0) (amplitude / (noiseFloor * 5f)).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Gunmetal)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fillFraction)
                .background(barColor)
        )
        // Noise floor marker
        if (noiseFloor > 0) {
            val markerFraction = (1f / 5f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(markerFraction),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(NeonOrange.copy(alpha = 0.8f))
                )
            }
        }
    }
}

@Composable
private fun PttButton(
    isPressed: Boolean,
    isVoxOpen: Boolean,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val isActive = isPressed || isVoxOpen

    val pulseTransition = rememberInfiniteTransition(label = "ptt")
    val scale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pttScale"
    )

    Button(
        onClick = {},  // handled by pointerInput below
        modifier = modifier
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    }
                )
            },
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isPressed  -> NeonOrange
                isVoxOpen  -> ElectricCyan
                else       -> Gunmetal
            }
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isActive) 8.dp else 2.dp)
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = "PTT",
            tint = if (isActive) TextPrimary else TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isPressed -> "TRANSMITTING"
                isVoxOpen -> "VOX ACTIVE"
                else      -> "HOLD TO TALK"
            },
            color = if (isActive) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

package com.ridervoice.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RouterState
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.SecondaryButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.DeviceSetupViewModel

@Composable
fun DeviceSetupScreen(
    convoyName: String,
    isHost: Boolean,
    viewModel: DeviceSetupViewModel = hiltViewModel(),
    onReady: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val routerState by viewModel.routerState.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()
    val requestedDevice by viewModel.requestedDevice.collectAsState()
    val routedInput by viewModel.routedInput.collectAsState()
    val routedOutput by viewModel.routedOutput.collectAsState()

    val isBluetoothActive = activeDevice is AudioDevice.BluetoothSco
    var selectedRouteType by remember(activeDevice) {
        mutableStateOf(
            when (activeDevice) {
                is AudioDevice.BluetoothSco -> "BLUETOOTH"
                is AudioDevice.Speakerphone -> "SPEAKER"
                is AudioDevice.WiredHeadset, is AudioDevice.UsbAudio -> "WIRED"
                else -> "BLUETOOTH"
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Editorial Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Headset & Audio",
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero Headset Status Bento Card
            item {
                val (statusText, statusColor) = when (routerState) {
                    RouterState.SCANNING -> "Searching for Bluetooth headset..." to InkSecondary
                    RouterState.CONNECTING_BT -> "Connecting Bluetooth audio..." to Pop
                    RouterState.FAILED -> "Bluetooth off or unpaired" to Brick
                    else -> "Headset audio ready" to Moss
                }

                Surface(
                    color = PaperRaised,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(136.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (routerState == RouterState.SCANNING || routerState == RouterState.CONNECTING_BT) {
                                SpinningRadar()
                                Icon(
                                    imageVector = Icons.Default.BluetoothSearching,
                                    contentDescription = "Scanning for Bluetooth headsets",
                                    tint = Pop,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else if (routerState == RouterState.FAILED) {
                                Box(
                                    modifier = Modifier
                                        .size(104.dp)
                                        .clip(CircleShape)
                                        .background(BrickContainer)
                                        .border(1.5.dp, Brick, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothDisabled,
                                        contentDescription = "Bluetooth connection error",
                                        tint = Brick,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(104.dp)
                                        .clip(CircleShape)
                                        .background(MossContainer)
                                        .border(1.5.dp, Moss.copy(alpha = 0.5f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HeadsetMic,
                                        contentDescription = "Headset connected",
                                        tint = Moss,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Routing to ${activeDevice.displayName()}",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        val isMismatch = requestedDevice != null &&
                            (requestedDevice is AudioDevice.BluetoothSco || requestedDevice is AudioDevice.WiredHeadset || requestedDevice is AudioDevice.UsbAudio) &&
                            (activeDevice !is AudioDevice.BluetoothSco && activeDevice !is AudioDevice.WiredHeadset && activeDevice !is AudioDevice.UsbAudio)

                        if (isMismatch) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = BrickContainer,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Brick),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Using phone audio, headset not active",
                                        color = Brick,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { viewModel.rescanAudioDevices() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Pop),
                                        shape = RoundedCornerShape(RvRadius.pill),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Retry", color = PaperOnPop, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            color = PaperSunken,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Mic: ${routedInput.display()}",
                                    color = if (isMismatch) Brick else Ink,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Speaker: ${routedOutput.display()}",
                                    color = if (isMismatch) Brick else Ink,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Manual Route Pill Chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AUDIO OUTPUT",
                        color = InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.9.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioRouteChip(
                            label = "Bluetooth",
                            icon = Icons.Default.Bluetooth,
                            isSelected = selectedRouteType == "BLUETOOTH",
                            modifier = Modifier.weight(1f)
                        ) {
                            selectedRouteType = "BLUETOOTH"
                            viewModel.setAudioRoute("BLUETOOTH")
                        }

                        AudioRouteChip(
                            label = "Speaker",
                            icon = Icons.Default.VolumeUp,
                            isSelected = selectedRouteType == "SPEAKER",
                            modifier = Modifier.weight(1f)
                        ) {
                            selectedRouteType = "SPEAKER"
                            viewModel.setAudioRoute("SPEAKER")
                        }

                        AudioRouteChip(
                            label = "Wired",
                            icon = Icons.Default.Headset,
                            isSelected = selectedRouteType == "WIRED",
                            modifier = Modifier.weight(1f)
                        ) {
                            selectedRouteType = "WIRED"
                            viewModel.setAudioRoute("WIRED")
                        }
                    }
                }
            }

            // Bluetooth Device Card
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BLUETOOTH DEVICE",
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 0.9.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        TextButton(
                            onClick = { viewModel.rescanAudioDevices() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Pop, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rescan", color = Pop, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        color = PaperRaised,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, if (isBluetoothActive) Moss.copy(alpha = 0.55f) else InkHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (isBluetoothActive) MossContainer else PaperSunken),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isBluetoothActive) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                                    contentDescription = null,
                                    tint = if (isBluetoothActive) Moss else InkSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBluetoothActive) activeDevice.displayName() else "No Bluetooth headset connected",
                                    color = Ink,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isBluetoothActive) {
                                        "Connected for voice intercom (Mic + Audio)"
                                    } else {
                                        "Audio routed to ${activeDevice.displayName()}"
                                    },
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            if (isBluetoothActive) {
                                Surface(
                                    color = MossContainer,
                                    shape = RoundedCornerShape(RvRadius.pill)
                                ) {
                                    Text(
                                        text = "Active",
                                        color = Moss,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SecondaryButton(
                    text = "Open Bluetooth Settings",
                    leadingIcon = Icons.Default.SettingsBluetooth,
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        val buttonLabel = when {
            convoyName == "GLOBAL" -> "Done"
            isHost -> "Continue to lobby"
            else -> "Ready to join convoy"
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            PrimaryButton(
                text = buttonLabel,
                onClick = {
                    viewModel.finishSetup()
                    onReady()
                }
            )
        }
    }
}

@Composable
fun AudioRouteChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) PopContainer else PaperSunken,
        shape = RoundedCornerShape(RvRadius.pill),
        border = BorderStroke(1.dp, if (isSelected) Pop.copy(alpha = 0.35f) else InkHairline),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Pop else InkSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = if (isSelected) Pop else InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun SpinningRadar() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarRotate"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarScale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAlpha"
    )

    val popColor = Pop
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Scanning for Bluetooth headsets" }
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val radius = (size.width / 2) * scale

        drawCircle(
            color = popColor.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 2.5.dp.toPx())
        )

        drawArc(
            color = popColor.copy(alpha = 0.25f),
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )
    }
}

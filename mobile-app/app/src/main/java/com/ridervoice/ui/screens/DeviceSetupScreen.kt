package com.ridervoice.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RouterState
import com.ridervoice.ui.components.TacticalButton
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
    val isDark = ThemeState.isDarkTheme

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
            .background(GraphiteBase)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "HARDWARE TRANSCEIVER SETUP",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "HELMET AUDIO & SCO",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.size(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (routerState == RouterState.SCANNING || routerState == RouterState.CONNECTING_BT) {
                        SpinningRadar()
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
                            contentDescription = "Scanning for Bluetooth headsets",
                            tint = NeonOrange,
                            modifier = Modifier.size(44.dp)
                        )
                    } else if (routerState == RouterState.FAILED) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(HazardContainer)
                                .border(2.dp, AlertRed, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = "Bluetooth connection error",
                                tint = AlertRed,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(DarkSlate)
                                .border(2.dp, TechGreen, CircleShape)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HeadsetMic,
                                contentDescription = "Headset connected",
                                tint = TechGreen,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                val (statusText, statusColor) = when (routerState) {
                    RouterState.SCANNING      -> "SEARCHING HELMET BLUETOOTH..." to ElectricCyan
                    RouterState.CONNECTING_BT -> "SYNCHRONIZING SCO CARRIER..." to NeonOrange
                    RouterState.FAILED        -> "BLUETOOTH OFF / UNPAIRED" to AlertRed
                    else                      -> "AUDIO HARNESS ACTIVE ✓" to TechGreen
                }

                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Routing to: ${activeDevice.displayName()}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "MANUAL AUDIO ROUTE OVERRIDE",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioRouteChip(
                        label = "BLUETOOTH",
                        icon = Icons.Default.Bluetooth,
                        isSelected = selectedRouteType == "BLUETOOTH",
                        modifier = Modifier.weight(1f)
                    ) {
                        selectedRouteType = "BLUETOOTH"
                        viewModel.setAudioRoute("BLUETOOTH")
                    }

                    AudioRouteChip(
                        label = "SPEAKER",
                        icon = Icons.Default.VolumeUp,
                        isSelected = selectedRouteType == "SPEAKER",
                        modifier = Modifier.weight(1f)
                    ) {
                        selectedRouteType = "SPEAKER"
                        viewModel.setAudioRoute("SPEAKER")
                    }

                    AudioRouteChip(
                        label = "WIRED",
                        icon = Icons.Default.Headset,
                        isSelected = selectedRouteType == "WIRED",
                        modifier = Modifier.weight(1f)
                    ) {
                        selectedRouteType = "WIRED"
                        viewModel.setAudioRoute("WIRED")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BLUETOOTH AUDIO DEVICE",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp
                    )
                    TextButton(
                        onClick = { viewModel.rescanAudioDevices() },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESCAN", color = NeonOrange, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isBluetoothActive) {
                    Surface(
                        color = DarkSlate,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TechGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Gunmetal),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothConnected,
                                    contentDescription = null,
                                    tint = TechGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeDevice.displayName(),
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Connected for tactical communications (Mic + Audio)",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp
                                )
                            }

                            Surface(
                                color = TechGreen.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    color = TechGreen,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        color = DarkSlate,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothDisabled,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "No Bluetooth Headset Connected",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Audio currently routed to ${activeDevice.displayName()}",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBluetooth,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PAIR / CONNECT AIRPODS & BLUETOOTH",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        val buttonLabel = when {
            convoyName == "GLOBAL" -> "DONE"
            isHost                 -> "CONTINUE TO LOBBY"
            else                   -> "READY TO JOIN CONVOY"
        }

        TacticalButton(
            text = buttonLabel,
            onClick = {
                viewModel.finishSetup()
                onReady()
            },
            enabled = true,
            color = NeonOrange,
            textColor = if (isDark) Color.Black else Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
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
    val isDark = ThemeState.isDarkTheme

    Surface(
        color = if (isSelected) (if (isDark) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt) else DarkSlate,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) NeonOrange else BorderColor
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) NeonOrange else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (isSelected) NeonOrange else TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
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
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarScale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAlpha"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Scanning for Bluetooth headsets" }
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val radius = (size.width / 2) * scale

        drawCircle(
            color = ElectricCyan.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )

        drawArc(
            color = ElectricCyan.copy(alpha = 0.4f),
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )
    }
}

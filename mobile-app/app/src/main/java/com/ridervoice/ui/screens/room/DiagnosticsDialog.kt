package com.ridervoice.ui.screens.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.audio.AudioDevice
import com.ridervoice.diagnostics.models.SelfMetricSample
import com.ridervoice.models.Participant
import com.ridervoice.ui.components.ParticipantNameResolver
import com.ridervoice.ui.theme.*

@Composable
fun DiagnosticsDialog(
    rideName: String,
    activeDevice: AudioDevice,
    latestSelfMetric: SelfMetricSample?,
    diagSampleCount: Int,
    participants: List<Participant>,
    currentUid: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = "Diagnostics",
                    tint = ElectricCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "RIDE DIAGNOSTICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonOrange,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = rideName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
                            text = "AUDIO ROUTE",
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
                            text = if (activeDevice is AudioDevice.BluetoothSco) "Wideband HFP/SCO Active (Headset)" else "Direct System Communication Route",
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
                            text = "NETWORK LINK QUALITY",
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
                            text = "DEVICE HEALTH",
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
                    text = "RIDERS IN SESSION (${participants.size})",
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
                        val resolvedName = if (rider.identity == currentUid) {
                            "You (${rider.displayName ?: "Local"})"
                        } else {
                            ParticipantNameResolver.resolve(rider.identity, rider.displayName)
                        }
                        Text(
                            text = resolvedName,
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
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkSlate
    )
}

@Composable
fun DiagnosticsConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* modal: require choice */ },
        title = {
            Text(
                text = "Share Ride Diagnostics?",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "The host has enabled diagnostics for this ride. Only network link metrics and device performance data are collected (zero audio content, zero GPS location). Data is automatically compiled and emailed to the project team upon ride conclusion to troubleshoot call drops.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = TechGreen)
            ) {
                Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text("Don't share my data", color = AlertRed)
            }
        },
        containerColor = DarkSlate
    )
}

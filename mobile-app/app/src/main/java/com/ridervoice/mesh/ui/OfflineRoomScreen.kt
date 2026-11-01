package com.ridervoice.mesh.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsSnapshot
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceTransport
import kotlinx.coroutines.launch

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun OfflineRoomScreen(
    sessionCode: String,
    transport: VoiceTransport,
    diagnosticsRepo: MeshDiagnosticsRepository? = null,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transportState by transport.state.collectAsState()
    val peers by transport.participants.collectAsState()
    val activeSpeakers by transport.activeSpeakers.collectAsState()
    val micState by transport.micState.collectAsState()
    val diagSnapshot = ((transport as? OfflineMeshVoiceTransport)?.diagnostics ?: diagnosticsRepo?.snapshot)?.collectAsState()?.value
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var isDeafened by remember { mutableStateOf(false) }
    var showDiagDialog by remember { mutableStateOf(false) }

    if (showDiagDialog) {
        MeshDiagnosticsDialog(
            snapshot = diagSnapshot,
            onDismiss = { showDiagDialog = false }
        )
    }

    Scaffold(
        topBar = {
            MeshTopBar(
                sessionCode = sessionCode,
                stateText = transportState.name,
                peerCount = peers.count { it.isOnline },
                onInfoClick = { showDiagDialog = true }
            )
        },
        bottomBar = {
            val isMicOn = micState == MicState.ON || micState == MicState.STARTING
            MeshBottomBar(
                isMicEnabled = isMicOn,
                isDeafened = isDeafened,
                onToggleMic = {
                    val next = !isMicOn
                    coroutineScope.launch {
                        transport.setMicrophoneEnabled(next)
                    }
                },
                onToggleDeafen = {
                    val next = !isDeafened
                    isDeafened = next
                    coroutineScope.launch {
                        transport.setDeafened(next)
                    }
                },
                onLeave = onLeave
            )
        },
        containerColor = Color(0xFF0F172A),
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (micState == MicState.PERMISSION_DENIED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Microphone permission required for mesh voice",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Open settings", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (micState == MicState.HARDWARE_BUSY) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Microphone is unavailable or busy",
                        color = Color(0xFFFDE68A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            // Mesh Topology Health Header
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "OFFLINE MULTI-HOP MESH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${peers.count { it.isOnline }} Active / ${peers.size} Discovered",
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                    val maxHops = peers.maxOfOrNull { it.hopCount } ?: 0
                    Surface(
                        color = Color(0xFF334155),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "MAX HOPS: $maxHops",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFA5B4FC),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Peer List
            Text(
                text = "MESH PEERS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(vertical = 4.dp),
                fontFamily = FontFamily.Monospace
            )

            val uniquePeers = remember(peers) { peers.distinctBy { it.id } }

            if (uniquePeers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Searching for nearby mesh riders...\nMake sure peers share the code: $sessionCode",
                        color = Color(0xFF64748B),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uniquePeers, key = { it.id }) { peer ->
                        MeshPeerCard(
                            peer = peer,
                            isSpeaking = peer.id in activeSpeakers
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshDiagnosticsDialog(
    snapshot: MeshDiagnosticsSnapshot?,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Mesh Telemetry",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            if (snapshot == null) {
                Text("No telemetry available yet", color = Color(0xFF94A3B8))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DiagRow("Device ID", snapshot.selfDeviceId.take(8))
                    DiagRow("Session ID", snapshot.sessionId.take(8))
                    DiagRow("Active Members", "${snapshot.memberCount}")
                    DiagRow("Direct Neighbors", "${snapshot.directNeighborCount}")
                    DiagRow("Active Routes", "${snapshot.routeCount}")
                    DiagRow("Max Route Hops", "${snapshot.maxHopCount}")
                    DiagRow("Voice Rx Frames", "${snapshot.receivedVoiceFrames}")
                    DiagRow("Voice Fwd Frames", "${snapshot.forwardedVoiceFrames}")
                    DiagRow("Dropped Duplicates", "${snapshot.droppedDuplicates}")
                    DiagRow("Dropped Invalid", "${snapshot.droppedInvalid}")
                    DiagRow("Dropped TTL", "${snapshot.droppedTtl}")
                    DiagRow("Graph Version", "${snapshot.graphVersion}")
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("CLOSE", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MeshPeerCard(
    peer: UnifiedVoicePeer,
    isSpeaking: Boolean
) {
    val borderColor = when {
        isSpeaking -> Color(0xFF10B981) // Emerald speaking highlight
        peer.isOnline -> Color(0xFF38BDF8) // Cyan connected
        else -> Color(0xFF475569) // Muted offline
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (peer.isOnline) Color(0xFF10B981) else Color(0xFFEF4444))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isSpeaking) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SPEAKING",
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))

                // Routing details
                val routeInfo = if (peer.isDirect) {
                    "DIRECT LINK (1 Hop)"
                } else if (peer.isRelayed) {
                    "RELAYED · ${peer.hopCount} Hops via ${peer.nextHopId?.take(8) ?: "relay"}"
                } else {
                    "UNREACHABLE"
                }
                Text(
                    text = routeInfo,
                    color = if (peer.isDirect) Color(0xFF38BDF8) else Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MeshTopBar(
    sessionCode: String,
    stateText: String,
    peerCount: Int,
    onInfoClick: () -> Unit
) {
    Surface(color = Color(0xFF0F172A), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CODE: $sessionCode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "STATUS: $stateText · $peerCount PEERS",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Diagnostics",
                    tint = Color(0xFF38BDF8)
                )
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun MeshBottomBar(
    isMicEnabled: Boolean,
    isDeafened: Boolean,
    onToggleMic: () -> Unit,
    onToggleDeafen: () -> Unit,
    onLeave: () -> Unit
) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mic Toggle
            IconButton(
                onClick = onToggleMic,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (isMicEnabled) Color(0xFF10B981) else Color(0xFF334155))
            ) {
                Icon(
                    imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Toggle Mic",
                    tint = Color.White
                )
            }

            // Deafen Toggle
            IconButton(
                onClick = onToggleDeafen,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (isDeafened) Color(0xFFEF4444) else Color(0xFF334155))
            ) {
                Icon(
                    imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = "Toggle Deafen",
                    tint = Color.White
                )
            }

            // Leave Session Button
            Surface(
                onClick = onLeave,
                color = Color(0xFFDC2626),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "LEAVE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
    }
}


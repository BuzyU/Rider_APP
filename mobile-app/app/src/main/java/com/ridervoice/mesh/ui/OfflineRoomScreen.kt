package com.ridervoice.mesh.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsSnapshot
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.flow.MutableStateFlow
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

    val meshTransport = transport as? OfflineMeshVoiceTransport
    val voxBridge = meshTransport?.voxBridge
    val isVoxEnabled by (voxBridge?.voxEnabled ?: MutableStateFlow(true)).collectAsState()
    val isPttActive by (voxBridge?.isPttActive ?: MutableStateFlow(false)).collectAsState()
    val activeRoute by (meshTransport?.audioRoute ?: MutableStateFlow(AudioRoute.SPEAKER)).collectAsState()

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
                transportState = transportState,
                peerCount = peers.count { it.isOnline },
                onInfoClick = { showDiagDialog = true }
            )
        },
        bottomBar = {
            val isMicOn = micState == MicState.ON || micState == MicState.STARTING
            MeshBottomBar(
                isMicEnabled = isMicOn,
                isDeafened = isDeafened,
                activeRoute = activeRoute,
                isVoxEnabled = isVoxEnabled,
                isPttActive = isPttActive,
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
                onCycleAudioRoute = {
                    val nextRoute = when (activeRoute) {
                        AudioRoute.SPEAKER -> AudioRoute.BLUETOOTH
                        AudioRoute.BLUETOOTH -> AudioRoute.WIRED
                        AudioRoute.WIRED -> AudioRoute.EARPIECE
                        AudioRoute.EARPIECE -> AudioRoute.SPEAKER
                    }
                    coroutineScope.launch {
                        transport.setAudioRoute(nextRoute)
                    }
                },
                onToggleVox = {
                    voxBridge?.setVoxEnabled(!isVoxEnabled)
                },
                onPttPressChange = { held ->
                    voxBridge?.setPttPressed(held)
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
                            text = stringResource(R.string.mesh_mic_permission_needed),
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
                            Text(stringResource(R.string.mesh_mic_open_settings), color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
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
                        text = stringResource(R.string.mesh_mic_busy),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (transportState == VoiceTransportState.CONNECTED) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.mesh_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.mesh_active_discovered, peers.count { it.isOnline }, peers.size),
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
                            text = stringResource(R.string.mesh_max_hops, maxHops),
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
                text = stringResource(R.string.mesh_peers_header),
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
                        text = stringResource(R.string.mesh_peers_searching, sessionCode),
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
                            isSpeaking = peer.id in activeSpeakers,
                            allPeers = uniquePeers
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MeshDiagnosticsDialog(
    snapshot: MeshDiagnosticsSnapshot?,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.mesh_diag_title),
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            if (snapshot == null) {
                Text(stringResource(R.string.mesh_diag_empty), color = Color(0xFF94A3B8))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DiagRow(stringResource(R.string.mesh_diag_device_id), snapshot.selfDeviceId.take(8))
                    DiagRow(stringResource(R.string.mesh_diag_session_id), snapshot.sessionId.take(8))
                    DiagRow(stringResource(R.string.mesh_diag_members), "${snapshot.memberCount}")
                    DiagRow(stringResource(R.string.mesh_diag_direct_neighbors), "${snapshot.directNeighborCount}")
                    DiagRow(stringResource(R.string.mesh_diag_routes), "${snapshot.routeCount}")
                    DiagRow(stringResource(R.string.mesh_diag_max_hops), "${snapshot.maxHopCount}")
                    DiagRow(stringResource(R.string.mesh_diag_rx_frames), "${snapshot.receivedVoiceFrames}")
                    DiagRow(stringResource(R.string.mesh_diag_fwd_frames), "${snapshot.forwardedVoiceFrames}")
                    DiagRow(stringResource(R.string.mesh_diag_dropped_dups), "${snapshot.droppedDuplicates}")
                    DiagRow(stringResource(R.string.mesh_diag_dropped_invalid), "${snapshot.droppedInvalid}")
                    DiagRow(stringResource(R.string.mesh_diag_dropped_ttl), "${snapshot.droppedTtl}")
                    DiagRow(stringResource(R.string.mesh_diag_graph_version), "${snapshot.graphVersion}")
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mesh_close), color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
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
    isSpeaking: Boolean,
    allPeers: List<UnifiedVoicePeer>
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
                            text = stringResource(R.string.mesh_speaking),
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))

                // Routing details with relay resolution and route age
                val relayName = if (peer.isRelayed && peer.nextHopId != null) {
                    allPeers.find { it.id == peer.nextHopId }?.name ?: peer.nextHopId.take(8)
                } else null

                val routeInfo = when {
                    peer.isDirect -> stringResource(R.string.mesh_direct_link)
                    peer.isRelayed -> stringResource(R.string.mesh_relayed_via, peer.hopCount, relayName ?: "relay")
                    else -> stringResource(R.string.mesh_unreachable)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = routeInfo,
                        color = if (peer.isDirect) Color(0xFF38BDF8) else Color(0xFFFBBF24),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val ageSec = (peer.routeAgeMs / 1000).coerceAtLeast(0)
                    Text(
                        text = stringResource(R.string.mesh_route_age, ageSec),
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshTopBar(
    sessionCode: String,
    transportState: VoiceTransportState,
    peerCount: Int,
    onInfoClick: () -> Unit
) {
    val stateText = when (transportState) {
        VoiceTransportState.STOPPED -> stringResource(R.string.mesh_state_stopped)
        VoiceTransportState.STARTING -> stringResource(R.string.mesh_state_starting)
        VoiceTransportState.CONNECTED -> stringResource(R.string.mesh_state_connected)
        VoiceTransportState.RECONNECTING -> stringResource(R.string.mesh_state_reconnecting)
        VoiceTransportState.FAILED -> stringResource(R.string.mesh_state_failed)
    }

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
                    text = stringResource(R.string.mesh_code_label, sessionCode),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(R.string.mesh_status_summary, stateText, peerCount),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.mesh_cd_diagnostics),
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
    activeRoute: AudioRoute,
    isVoxEnabled: Boolean,
    isPttActive: Boolean,
    onToggleMic: () -> Unit,
    onToggleDeafen: () -> Unit,
    onCycleAudioRoute: () -> Unit,
    onToggleVox: () -> Unit,
    onPttPressChange: (Boolean) -> Unit,
    onLeave: () -> Unit
) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Secondary row: Audio Route selector + VOX toggle + PTT indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Audio Route Cycle Button
                val (routeIcon, routeLabelRes) = when (activeRoute) {
                    AudioRoute.SPEAKER -> Icons.Default.VolumeUp to R.string.mesh_audio_route_speaker
                    AudioRoute.BLUETOOTH -> Icons.Default.Bluetooth to R.string.mesh_audio_route_bluetooth
                    AudioRoute.WIRED -> Icons.Default.Headset to R.string.mesh_audio_route_wired
                    AudioRoute.EARPIECE -> Icons.Default.PhoneInTalk to R.string.mesh_audio_route_earpiece
                }
                Surface(
                    onClick = onCycleAudioRoute,
                    color = Color(0xFF334155),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = routeIcon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(routeLabelRes),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // VOX Toggle
                Surface(
                    onClick = onToggleVox,
                    color = if (isVoxEnabled) Color(0xFF065F46) else Color(0xFF334155),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isVoxEnabled) stringResource(R.string.mesh_vox_on) else stringResource(R.string.mesh_vox_off),
                        color = if (isVoxEnabled) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // PTT Press-and-Hold Button
                Surface(
                    color = if (isPttActive) Color(0xFF047857) else Color(0xFF334155),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPttPressChange(true)
                                tryAwaitRelease()
                                onPttPressChange(false)
                            }
                        )
                    }
                ) {
                    Text(
                        text = if (isPttActive) stringResource(R.string.mesh_ptt_transmitting) else stringResource(R.string.mesh_ptt_button),
                        color = if (isPttActive) Color(0xFF6EE7B7) else Color(0xFFF1F5F9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Primary control row: Mic + Deafen + Leave
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Toggle
                IconButton(
                    onClick = onToggleMic,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (isMicEnabled) Color(0xFF10B981) else Color(0xFF334155))
                ) {
                    Icon(
                        imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = stringResource(R.string.mesh_cd_toggle_mic),
                        tint = Color.White
                    )
                }

                // Deafen Toggle
                IconButton(
                    onClick = onToggleDeafen,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (isDeafened) Color(0xFFEF4444) else Color(0xFF334155))
                ) {
                    Icon(
                        imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = stringResource(R.string.mesh_cd_toggle_deafen),
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
                        text = stringResource(R.string.mesh_leave),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

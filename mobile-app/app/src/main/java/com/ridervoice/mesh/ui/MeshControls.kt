package com.ridervoice.mesh.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsSnapshot
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceTransportState

@Composable
fun MeshDiagnosticsDialog(
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
                Text(stringResource(R.string.mesh_diag_empty), color = MeshColors.Slate)
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
                    DiagRow(stringResource(R.string.mesh_diag_dropped_buffer), "${snapshot.droppedAtLinkBuffer}")
                    DiagRow(stringResource(R.string.mesh_diag_graph_version), "${snapshot.graphVersion}")
                    DiagRow(stringResource(R.string.mesh_diag_link_state), snapshot.linkState)
                    DiagRow(stringResource(R.string.mesh_diag_local_ip), if (snapshot.localIp.isNotBlank()) snapshot.localIp else "None")
                    DiagRow(stringResource(R.string.mesh_diag_broadcast), if (snapshot.broadcastAddress.isNotBlank()) snapshot.broadcastAddress else "None")
                    if (snapshot.isolationWarningHint != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.mesh_selftest_no_neighbours_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshColors.AlertRed
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mesh_close), color = MeshColors.Cyan, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = MeshColors.SurfaceDark
    )
}

@Composable
fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MeshColors.Slate, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MeshPeerCard(
    peer: UnifiedVoicePeer,
    isSpeaking: Boolean,
    allPeers: List<UnifiedVoicePeer>,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isSpeaking -> MeshColors.Emerald
        peer.isOnline -> MeshColors.Cyan
        else -> MeshColors.SlateDark
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
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
                    .background(if (peer.isOnline) MeshColors.Emerald else MeshColors.Red)
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
                            color = MeshColors.Emerald,
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
                    peer.isRelayed && relayName != null -> stringResource(R.string.mesh_relayed_via, peer.hopCount, relayName)
                    peer.isRelayed -> stringResource(R.string.mesh_relayed_hops, peer.hopCount)
                    else -> stringResource(R.string.mesh_unreachable)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = routeInfo,
                        color = if (peer.isDirect) MeshColors.Cyan else MeshColors.AmberYellow,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val ageSec = (peer.routeAgeMs / 1000).coerceAtLeast(0)
                    Text(
                        text = stringResource(R.string.mesh_route_age, ageSec),
                        color = MeshColors.SlateMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTopBar(
    sessionCode: String,
    transportState: VoiceTransportState,
    peerCount: Int,
    onInfoClick: () -> Unit,
    onSosClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val stateText = when (transportState) {
        VoiceTransportState.STOPPED -> stringResource(R.string.mesh_state_stopped)
        VoiceTransportState.STARTING -> stringResource(R.string.mesh_state_starting)
        VoiceTransportState.CONNECTED -> stringResource(R.string.mesh_state_connected)
        VoiceTransportState.RECONNECTING -> stringResource(R.string.mesh_state_reconnecting)
        VoiceTransportState.FAILED -> stringResource(R.string.mesh_state_failed)
    }

    Surface(color = MeshColors.BackgroundDark, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = sessionCode,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(R.string.mesh_status_summary, stateText, peerCount),
                    fontSize = 11.sp,
                    color = MeshColors.Slate,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSosClick != null) {
                    Surface(
                        color = MeshColors.RedCrimson,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { onSosClick() }
                    ) {
                        Text(
                            text = stringResource(R.string.mesh_sos),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                IconButton(onClick = onInfoClick) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.mesh_cd_diagnostics),
                        tint = MeshColors.Cyan
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshBottomBar(
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
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MeshColors.SurfaceDark,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth()
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
                    color = MeshColors.SurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onCycleAudioRoute() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = routeIcon, contentDescription = null, tint = MeshColors.Cyan, modifier = Modifier.size(16.dp))
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
                    color = if (isVoxEnabled) MeshColors.EmeraldDark else MeshColors.SurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onToggleVox() }
                ) {
                    Text(
                        text = if (isVoxEnabled) stringResource(R.string.mesh_vox_on) else stringResource(R.string.mesh_vox_off),
                        color = if (isVoxEnabled) MeshColors.EmeraldLight else MeshColors.Slate,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // PTT Press-and-Hold Button
                Surface(
                    color = if (isPttActive) MeshColors.EmeraldActive else MeshColors.SurfaceElevated,
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
                        color = if (isPttActive) MeshColors.EmeraldLight else MeshColors.SlateLight,
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
                        .background(if (isMicEnabled) MeshColors.Emerald else MeshColors.SurfaceElevated)
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
                        .background(if (isDeafened) MeshColors.Red else MeshColors.SurfaceElevated)
                ) {
                    Icon(
                        imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = stringResource(R.string.mesh_cd_toggle_deafen),
                        tint = Color.White
                    )
                }

                // Leave Session Button
                Surface(
                    color = MeshColors.RedCrimson,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onLeave() }
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

@Composable
fun MeshHealthHeader(
    transportState: VoiceTransportState,
    peerCount: Int,
    discoveredCount: Int,
    maxHops: Int,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
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
                            .background(if (transportState == VoiceTransportState.CONNECTED) MeshColors.Emerald else MeshColors.Amber)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.mesh_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MeshColors.Cyan,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.mesh_active_discovered, peerCount, discoveredCount),
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
            Surface(
                color = MeshColors.SurfaceElevated,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.mesh_max_hops, maxHops),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MeshColors.Indigo,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun MicPermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.RedDark),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
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
                Text(stringResource(R.string.mesh_mic_open_settings), color = MeshColors.RedLight, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MicBusyCard(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.AmberDark),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.mesh_mic_busy),
            color = MeshColors.AmberLight,
            fontSize = 13.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Full-screen, opaque, touch-consuming overlay active during fallback.
 * Renders mesh peer list, health, controls, Leave and SOS buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshActiveOverlay(
    autoSwitchState: AutoSwitchState,
    transportState: VoiceTransportState,
    peers: List<UnifiedVoicePeer>,
    activeSpeakers: Set<String>,
    micState: MicState,
    micToggleOn: Boolean,
    isPttActive: Boolean,
    isVoxEnabled: Boolean,
    isDeafened: Boolean,
    activeRoute: AudioRoute,
    onToggleMic: () -> Unit,
    onToggleDeafen: () -> Unit,
    onCycleAudioRoute: () -> Unit,
    onToggleVox: () -> Unit,
    onPttPressChange: (Boolean) -> Unit,
    onViewDiagnostics: () -> Unit,
    onLeave: () -> Unit,
    onSosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (autoSwitchState) {
        AutoSwitchState.STARTING_OFFLINE_MESH -> stringResource(R.string.mesh_banner_starting_offline)
        AutoSwitchState.OFFLINE_ACTIVE -> stringResource(R.string.mesh_banner_offline_active)
        AutoSwitchState.ONLINE_RECOVERING -> stringResource(R.string.mesh_banner_online_restored)
        AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> stringResource(R.string.mesh_banner_handing_back)
        else -> stringResource(R.string.mesh_title)
    }

    val isMicOn = micToggleOn || isPttActive || micState == MicState.ON || micState == MicState.STARTING
    val uniquePeers = remember(peers) { peers.distinctBy { it.id } }
    val maxHops = peers.maxOfOrNull { it.hopCount } ?: 0

    Surface(
        color = MeshColors.BackgroundDark,
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // Consume all touches to prevent click-through to RoomScreen
            )
    ) {
        Scaffold(
            topBar = {
                MeshTopBar(
                    sessionCode = title,
                    transportState = transportState,
                    peerCount = peers.count { it.isOnline },
                    onInfoClick = onViewDiagnostics,
                    onSosClick = onSosClick
                )
            },
            bottomBar = {
                MeshBottomBar(
                    isMicEnabled = isMicOn,
                    isDeafened = isDeafened,
                    activeRoute = activeRoute,
                    isVoxEnabled = isVoxEnabled,
                    isPttActive = isPttActive,
                    onToggleMic = onToggleMic,
                    onToggleDeafen = onToggleDeafen,
                    onCycleAudioRoute = onCycleAudioRoute,
                    onToggleVox = onToggleVox,
                    onPttPressChange = onPttPressChange,
                    onLeave = onLeave
                )
            },
            containerColor = MeshColors.BackgroundDark,
            modifier = Modifier.fillMaxSize()
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (micState == MicState.PERMISSION_DENIED) {
                    MicPermissionCard()
                } else if (micState == MicState.HARDWARE_BUSY) {
                    MicBusyCard()
                }

                MeshHealthHeader(
                    transportState = transportState,
                    peerCount = peers.count { it.isOnline },
                    discoveredCount = peers.size,
                    maxHops = maxHops
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.mesh_peers_header),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MeshColors.Slate,
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontFamily = FontFamily.Monospace
                )

                if (uniquePeers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.mesh_peers_searching, title),
                            color = MeshColors.SlateMuted,
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
}

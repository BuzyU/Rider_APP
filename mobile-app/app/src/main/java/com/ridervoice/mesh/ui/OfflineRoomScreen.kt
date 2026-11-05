package com.ridervoice.mesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun OfflineRoomScreen(
    sessionCode: String,
    transport: VoiceTransport,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    diagnosticsRepo: MeshDiagnosticsRepository? = null
) {
    val transportState by transport.state.collectAsState()
    val peers by transport.participants.collectAsState()
    val activeSpeakers by transport.activeSpeakers.collectAsState()
    val micState by transport.micState.collectAsState()
    val diagSnapshot = ((transport as? OfflineMeshVoiceTransport)?.diagnostics ?: diagnosticsRepo?.snapshot)?.collectAsState()?.value
    val coroutineScope = rememberCoroutineScope()

    val meshTransport = transport as? OfflineMeshVoiceTransport
    val micController = meshTransport?.micController
    val voxBridge = meshTransport?.voxBridge
    val micToggleOn by (micController?.micToggleOn ?: MutableStateFlow(false)).collectAsState()
    val isVoxEnabled by (micController?.voxEnabled ?: voxBridge?.voxEnabled ?: MutableStateFlow(false)).collectAsState()
    val isPttActive by (micController?.pttHeld ?: voxBridge?.isPttActive ?: MutableStateFlow(false)).collectAsState()
    val activeRoute by (meshTransport?.audioRoute ?: MutableStateFlow(AudioRoute.SPEAKER)).collectAsState()
    val transportDeafened by (meshTransport?.deafened ?: MutableStateFlow(false)).collectAsState()

    var localDeafened by remember { mutableStateOf(false) }
    val isDeafened = if (meshTransport != null) transportDeafened else localDeafened
    var showDiagDialog by remember { mutableStateOf(false) }

    BackHandler {
        onLeave()
    }

    if (showDiagDialog) {
        MeshDiagnosticsDialog(
            snapshot = diagSnapshot,
            onDismiss = { showDiagDialog = false }
        )
    }

    Scaffold(
        topBar = {
            MeshTopBar(
                sessionCode = stringResource(R.string.mesh_code_label, sessionCode),
                transportState = transportState,
                peerCount = peers.count { it.isOnline },
                onInfoClick = { showDiagDialog = true }
            )
        },
        bottomBar = {
            val isMicOn = micToggleOn || isPttActive || micState == MicState.ON || micState == MicState.STARTING
            MeshBottomBar(
                isMicEnabled = isMicOn,
                isDeafened = isDeafened,
                activeRoute = activeRoute,
                isVoxEnabled = isVoxEnabled,
                isPttActive = isPttActive,
                onToggleMic = {
                    if (micController != null) {
                        micController.setMicToggled(!micToggleOn)
                    } else {
                        val next = !isMicOn
                        coroutineScope.launch {
                            transport.setMicrophoneEnabled(next)
                        }
                    }
                },
                onToggleDeafen = {
                    val next = !isDeafened
                    localDeafened = next
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
                    micController?.setVoxEnabled(!isVoxEnabled) ?: voxBridge?.setVoxEnabled(!isVoxEnabled)
                },
                onPttPressChange = { held ->
                    micController?.setPttPressed(held) ?: voxBridge?.setPttPressed(held)
                },
                onLeave = onLeave
            )
        },
        containerColor = MeshColors.BackgroundDark,
        modifier = modifier.fillMaxSize()
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

            val maxHops = peers.maxOfOrNull { it.hopCount } ?: 0
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

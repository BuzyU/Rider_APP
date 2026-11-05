package com.ridervoice.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ridervoice.R
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.GroupConnectivityStatus
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.ui.screens.RoomScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OfflineAwareRoomViewModel @Inject constructor(
    val autoSwitchManager: MeshAutoSwitchManager,
    val coordinator: VoiceSessionCoordinator,
    val meshTransport: OfflineMeshVoiceTransport
) : ViewModel() {
    val autoSwitchState: StateFlow<AutoSwitchState> = autoSwitchManager.autoSwitchState
    val groupStatus: StateFlow<GroupConnectivityStatus> = autoSwitchManager.groupStatus
    val meshParticipants = meshTransport.participants
    val meshDiagSnapshot = meshTransport.diagnostics
    val lastTransitionReason: StateFlow<String?> = autoSwitchManager.lastTransitionReason
    val offlineAvailability: StateFlow<OfflineAvailability> = coordinator.offlineAvailability
}

@Composable
fun OfflineAwareRoomHost(
    roomName: String,
    userName: String,
    onLeave: () -> Unit,
    onSosClick: () -> Unit,
    viewModel: OfflineAwareRoomViewModel = hiltViewModel()
) {
    val autoSwitchState by viewModel.autoSwitchState.collectAsState()
    val groupStatus by viewModel.groupStatus.collectAsState()
    val meshParticipants by viewModel.meshParticipants.collectAsState()
    val diagSnapshot by viewModel.meshDiagSnapshot.collectAsState()
    val lastTransitionReason by viewModel.lastTransitionReason.collectAsState()
    val offlineAvailability by viewModel.offlineAvailability.collectAsState()

    var showDiagDialog by remember { mutableStateOf(false) }

    val settingsSnapshot by OfflineMeshGate.settingsFlow.collectAsState()
    val isMeshGateOpen = OfflineMeshGate.isOfflineAllowed(offlineMeshEnabled = settingsSnapshot.offlineMeshEnabled)
    val showTacticalBanner = isMeshGateOpen && (
        autoSwitchState != AutoSwitchState.ONLINE ||
        groupStatus == GroupConnectivityStatus.MIXED
    )

    val showBackupNotReadyChip = isMeshGateOpen &&
        settingsSnapshot.automaticFallbackEnabled &&
        offlineAvailability == OfflineAvailability.NO_SHARED_SECRET &&
        autoSwitchState == AutoSwitchState.ONLINE

    val fallbackReason = remember(lastTransitionReason, offlineAvailability) {
        mapTransitionReasonToFallbackReason(lastTransitionReason, offlineAvailability)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RoomScreen(
            roomName = roomName,
            userName = userName,
            onLeave = onLeave,
            onSosClick = onSosClick
        )

        if (showBackupNotReadyChip && !showTacticalBanner) {
            OfflineBackupChip(
                text = stringResource(R.string.mesh_backup_not_ready),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
            )
        }

        if (showTacticalBanner) {
            TacticalBanner(
                state = autoSwitchState,
                groupStatus = groupStatus,
                peerCount = meshParticipants.count { it.isOnline },
                maxHops = meshParticipants.maxOfOrNull { it.hopCount } ?: 0,
                fallbackReason = fallbackReason,
                onViewDiagnostics = { showDiagDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 12.dp, end = 12.dp, bottom = 6.dp)
            )
        }

        if (showDiagDialog) {
            MeshDiagnosticsDialog(
                snapshot = diagSnapshot,
                onDismiss = { showDiagDialog = false }
            )
        }
    }
}

@Composable
private fun OfflineBackupChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MeshColors.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MeshColors.Amber)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = MeshColors.AmberYellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun TacticalBanner(
    state: AutoSwitchState,
    groupStatus: GroupConnectivityStatus,
    peerCount: Int,
    maxHops: Int,
    fallbackReason: FallbackReason = FallbackReason.UNKNOWN,
    onViewDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = when (state) {
        AutoSwitchState.OFFLINE_ACTIVE -> stringResource(R.string.mesh_banner_offline_active) to MeshColors.Emerald
        AutoSwitchState.ONLINE_SUSPECT -> stringResource(R.string.mesh_banner_checking_connection) to MeshColors.Amber
        AutoSwitchState.STARTING_OFFLINE_MESH -> stringResource(R.string.mesh_banner_starting_offline) to MeshColors.Amber
        AutoSwitchState.ONLINE_RECOVERING -> stringResource(R.string.mesh_banner_online_restored) to MeshColors.Emerald
        AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> stringResource(R.string.mesh_banner_handing_back) to MeshColors.Cyan
        AutoSwitchState.FALLBACK_UNAVAILABLE -> {
            val text = when (fallbackReason) {
                FallbackReason.NO_BACKUP_CODE -> stringResource(R.string.mesh_fallback_reason_no_backup_code)
                FallbackReason.NO_RIDERS_NEARBY -> stringResource(R.string.mesh_fallback_reason_no_riders_nearby)
                FallbackReason.HANDOFF_FAILED -> stringResource(R.string.mesh_fallback_reason_handoff_failed)
                FallbackReason.UNKNOWN -> stringResource(R.string.mesh_banner_fallback_unavailable)
            }
            text to MeshColors.Red
        }
        AutoSwitchState.ONLINE -> stringResource(R.string.mesh_state_connected) to MeshColors.Emerald
        AutoSwitchState.SESSION_ENDED -> stringResource(R.string.mesh_state_stopped) to MeshColors.Slate
    }

    val groupBadgeText = when (groupStatus) {
        GroupConnectivityStatus.ONLINE_ALL -> stringResource(R.string.mesh_group_entire_online)
        GroupConnectivityStatus.MIXED -> stringResource(R.string.mesh_group_some_offline)
        GroupConnectivityStatus.OFFLINE_MESH_ONLY -> stringResource(R.string.mesh_group_offline_active)
        GroupConnectivityStatus.ALONE -> stringResource(R.string.mesh_group_entire_online)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.CardBackground),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = MeshColors.SurfaceDark,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable { onViewDiagnostics() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.mesh_banner_view_mesh),
                            tint = MeshColors.Cyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.mesh_banner_view_mesh),
                            fontSize = 10.sp,
                            color = MeshColors.Cyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = groupBadgeText,
                    color = if (groupStatus == GroupConnectivityStatus.MIXED) MeshColors.AmberYellow else MeshColors.Slate,
                    fontSize = 11.sp
                )
                if (state == AutoSwitchState.OFFLINE_ACTIVE) {
                    Text(
                        text = stringResource(R.string.mesh_status_summary, "$peerCount active", maxHops),
                        color = MeshColors.Indigo,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

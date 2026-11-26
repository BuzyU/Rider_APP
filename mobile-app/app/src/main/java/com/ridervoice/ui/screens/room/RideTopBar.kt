package com.ridervoice.ui.screens.room

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.ParticipantNameResolver
import com.ridervoice.ui.theme.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.traversalIndex

@Composable
fun RideTopBar(
    rideName: String,
    connectionState: ConnectionState,
    activeSpeaker: String?,
    currentUid: String,
    participants: List<Participant>,
    activeDevice: AudioDevice,
    routerState: RouterState,
    requestedDevice: AudioDevice?,
    routedInput: RoutedDeviceInfo,
    routedOutput: RoutedDeviceInfo,
    isCollectingDiag: Boolean,
    diagSampleCount: Int,
    onAudioRouteClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onRescanAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rawFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val isLargeFont = rawFontScale > 1.3f

    CapChromeFontScale(maxScale = 1.3f) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RvDimens.rideTopBarHeight)
                    .padding(horizontal = 4.dp)
                    .semantics { isTraversalGroup = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Fixed-width slot (116dp) dedicated to ConnectionPill so status text never
                // squeezes against DIAG and center/right siblings never shift.
                Box(
                    modifier = Modifier
                        .width(116.dp)
                        .fillMaxHeight()
                        .semantics { traversalIndex = 1f }
                        .testTag("ride_topbar_left"),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ConnectionPill(connectionState)
                }

                // Center: Ride Name & Active Speaker / Rider Count (>= 80dp even on 320dp viewports)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { traversalIndex = 1.5f }
                        .testTag("ride_topbar_center")
                        .padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = rideName,
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val speakerText = if (activeSpeaker != null) {
                        val spk = participants.find { it.identity == activeSpeaker }
                        val resolvedName = if (activeSpeaker == currentUid) {
                            stringResource(R.string.participant_you)
                        } else {
                            ParticipantNameResolver.resolve(activeSpeaker, spk?.displayName)
                        }
                        stringResource(R.string.room_speaker_speaking, resolvedName)
                    } else if (!isLargeFont) {
                        val count = participants.count { !it.isGhost }
                        pluralStringResource(R.plurals.riders_in_voice_count, count, count)
                    } else {
                        val count = participants.count { !it.isGhost }
                        stringResource(R.string.room_riders_in_voice_short, count)
                    }
                    Text(
                        text = speakerText,
                        color = if (activeSpeaker != null) Pop else InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (activeSpeaker != null) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .heightIn(min = 16.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                // Right: Fixed-width slot (48dp Audio + 16dp clear gap + 48x56dp Leave = 112dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(112.dp)
                        .fillMaxHeight()
                        .testTag("ride_topbar_right"),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(48.dp)
                            .semantics { traversalIndex = 4.5f }
                            .clickable(role = Role.Button) { onAudioRouteClick() }
                            .testTag("ride_topbar_audio_badge"),
                        contentAlignment = Alignment.Center
                    ) {
                        AudioDeviceBadge(
                            device = activeDevice,
                            routerState = routerState,
                            requestedDevice = requestedDevice,
                            routedInput = routedInput,
                            routedOutput = routedOutput,
                            onRetry = onRescanAudio
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    val leaveVoiceCd = stringResource(R.string.cd_leave_voice_call)
                    IconButton(
                        onClick = onLeaveClick,
                        modifier = Modifier
                            .requiredSize(width = 48.dp, height = 56.dp)
                            .clip(CircleShape)
                            .background(BrickContainer)
                            .border(1.5.dp, Brick, CircleShape)
                            .semantics {
                                contentDescription = leaveVoiceCd
                                traversalIndex = 6f
                            }
                            .testTag("ride_topbar_leave_button")
                    ) {
                        Icon(
                            imageVector = RvIcons.Leave,
                            contentDescription = stringResource(R.string.cd_leave_ride),
                            tint = Brick,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            if (isCollectingDiag) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .widthIn(min = 48.dp)
                        .height(48.dp)
                        .testTag("ride_topbar_diag_pill")
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrickContainer)
                        .border(1.dp, Brick, RoundedCornerShape(8.dp))
                        .semantics { traversalIndex = 4.8f }
                        .clickable(role = Role.Button) { onDiagnosticsClick() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Brick)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (diagSampleCount > 0) stringResource(R.string.room_diag_count, diagSampleCount) else stringResource(R.string.room_diag_rec),
                        color = Brick,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("ride_topbar_diag_label")
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionPill(state: ConnectionState) {
    val rvHaptics = rememberRvHapticsController()
    LaunchedEffect(state) {
        rvHaptics.onConnectionTransition(state)
    }
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> StatusOk     to stringResource(R.string.room_conn_connected)
        ConnectionState.CONNECTING   -> StatusWarn   to stringResource(R.string.room_conn_connecting)
        ConnectionState.RECONNECTING -> StatusWarn   to stringResource(R.string.room_conn_reconnecting)
        ConnectionState.FAILED       -> Brick        to stringResource(R.string.room_conn_no_signal)
        ConnectionState.DISCONNECTED -> InkSecondary to stringResource(R.string.room_conn_offline)
    }

    val shouldPulse = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING
    val reduceMotion = rememberIsReduceMotionEnabled()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        if (shouldPulse && !reduceMotion) {
            PulsingConnectionDot(color = color)
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .drawBehind { drawCircle(color = color) }
                    .testTag("connection_dot_static")
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            style = RvTypography.microBadge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("ride_topbar_conn_label")
        )
    }
}

@Composable
private fun PulsingConnectionDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha = pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .drawBehind { drawCircle(color = color.copy(alpha = alpha.value)) }
            .testTag("connection_dot_pulsing")
    )
}

@Composable
fun AudioDeviceBadge(
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (isMismatch) BrickContainer else PaperRaised)
            .border(1.dp, if (isMismatch) Brick else InkHairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        if (isMismatch) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.room_headset_inactive),
                    color = Brick,
                    style = RvTypography.microBadge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pop)
                ) {
                    Text(stringResource(R.string.action_retry), style = RvTypography.microBadge, color = PaperOnPop)
                }
            }
        } else if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
            Text(
                text = routedOutput.display(),
                color = InkSecondary,
                style = RvTypography.microBadge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = stringResource(R.string.device_mic_label, routedInput.display()),
                color = InkSecondary,
                style = RvTypography.microBadge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.device_audio_label, routedOutput.display()),
                color = InkSecondary,
                style = RvTypography.microBadge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

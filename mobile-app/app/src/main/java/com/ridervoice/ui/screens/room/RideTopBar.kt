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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.ParticipantNameResolver
import com.ridervoice.ui.theme.*

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
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val isLargeFont = fontScale > 1.3f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = if (isLargeFont) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Connection Pill & optional Diagnostics pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .requiredWidthIn(min = if (isCollectingDiag) 120.dp else 80.dp)
                .weight(1f, fill = false)
                .testTag("ride_topbar_left")
        ) {
            ConnectionPill(connectionState)

            if (isCollectingDiag) {
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrickContainer)
                        .border(1.dp, Brick, RoundedCornerShape(6.dp))
                        .clickable { onDiagnosticsClick() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Brick)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (diagSampleCount > 0) "DIAG ($diagSampleCount)" else "REC",
                        color = Brick,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Center: Ride Name & Active Speaker / Rider Count
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1.5f)
                .padding(horizontal = 6.dp)
                .testTag("ride_topbar_center")
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
                    "You"
                } else {
                    ParticipantNameResolver.resolve(activeSpeaker, spk?.displayName)
                }
                "$resolvedName is speaking"
            } else if (!isLargeFont) {
                val count = participants.count { !it.isGhost }
                if (count == 1) "1 rider in voice" else "$count riders in voice"
            } else {
                val count = participants.count { !it.isGhost }
                "$count in voice"
            }
            Text(
                text = speakerText,
                color = if (activeSpeaker != null) Pop else InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (activeSpeaker != null) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 16.dp)
            )
        }

        // Right: Audio Device Badge & Leave Call button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .requiredWidthIn(min = 104.dp)
                .testTag("ride_topbar_right"),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .widthIn(max = 120.dp)
                    .clickable { onAudioRouteClick() }
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

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onLeaveClick,
                modifier = Modifier
                    .requiredSize(48.dp)
                    .clip(CircleShape)
                    .background(BrickContainer)
                    .border(1.5.dp, Brick, CircleShape)
                    .semantics { contentDescription = "Leave Voice Call" }
                    .testTag("ride_topbar_leave_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Leave Ride",
                    tint = Brick,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ConnectionPill(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> Ink          to "CONNECTED"
        ConnectionState.CONNECTING   -> Pop          to "CONNECTING"
        ConnectionState.RECONNECTING -> Pop          to "RECONNECTING"
        ConnectionState.FAILED       -> Brick        to "NO SIGNAL"
        ConnectionState.DISCONNECTED -> InkSecondary to "OFFLINE"
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTED) 0.45f else 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
                    text = "Headset inactive",
                    color = Brick,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                    modifier = Modifier.height(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pop)
                ) {
                    Text("Retry", fontSize = 8.sp, color = PaperOnPop)
                }
            }
        } else if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
            Text(
                text = routedOutput.display(),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = "Mic: ${routedInput.display()}",
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Audio: ${routedOutput.display()}",
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

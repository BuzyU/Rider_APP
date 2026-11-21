package com.ridervoice.ui.screens.room

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.theme.*

@Composable
fun TalkControls(
    isMicEnabled: Boolean,
    isDeafened: Boolean,
    isPttPressed: Boolean,
    isVoxOpen: Boolean,
    onToggleMic: () -> Unit,
    onToggleDeafen: () -> Unit,
    onPttPressStart: () -> Unit,
    onPttPressEnd: () -> Unit,
    onSosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RvTouchTarget.ptt),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Mic toggle button (56dp ride-time primary touch target)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(58.dp)
        ) {
            FilledIconButton(
                onClick = onToggleMic,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("talk_controls_mic")
                    .border(
                        1.5.dp,
                        if (isMicEnabled) Pop else InkHairline,
                        CircleShape
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isMicEnabled) PopContainer else PaperSunken
                )
            ) {
                Icon(
                    imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (isMicEnabled) stringResource(R.string.cd_mic_on_tap_mute) else stringResource(R.string.cd_mic_muted_tap_on),
                    tint = if (isMicEnabled) Pop else InkSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            CapChromeFontScale(maxScale = 1.3f) {
                Text(
                    text = if (isMicEnabled) stringResource(R.string.talk_mic_on) else stringResource(R.string.talk_mic_muted),
                    modifier = Modifier.testTag("talk_controls_mic_label"),
                    color = if (isMicEnabled) Pop else InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(2.dp))

        // 2. Deafen toggle button (56dp ride-time primary touch target)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(58.dp)
        ) {
            FilledIconButton(
                onClick = onToggleDeafen,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("talk_controls_deafen")
                    .border(
                        1.5.dp,
                        if (isDeafened) Brick else InkHairline,
                        CircleShape
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isDeafened) BrickContainer else PaperSunken
                )
            ) {
                Icon(
                    imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isDeafened) stringResource(R.string.cd_deafened_tap_hear) else stringResource(R.string.cd_tap_to_deafen),
                    tint = if (isDeafened) Brick else InkSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            CapChromeFontScale(maxScale = 1.3f) {
                Text(
                    text = if (isDeafened) stringResource(R.string.talk_deafened) else stringResource(R.string.talk_hearing),
                    modifier = Modifier.testTag("talk_controls_deafen_label"),
                    color = if (isDeafened) Brick else InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(2.dp))

        // 3. PTT Capsule Button (76dp height, gloved-hand friendly)
        PttPerforatedButton(
            isPressed = isPttPressed,
            isVoxOpen = isVoxOpen,
            modifier = Modifier
                .weight(1f)
                .height(RvTouchTarget.ptt),
            onPressStart = onPttPressStart,
            onPressEnd = onPttPressEnd
        )

        // Guaranteed >= 16dp clear separation between PTT and Emergency SOS
        Spacer(modifier = Modifier.width(16.dp))

        // 4. Emergency SOS Button (56dp ride-time primary touch target)
        val sosBtnCd = stringResource(R.string.cd_emergency_sos_button)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(56.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .testTag("talk_controls_sos")
                    .clip(RoundedCornerShape(14.dp))
                    .background(BrickContainer)
                    .border(2.dp, Brick, RoundedCornerShape(14.dp))
                    .clickable { onSosClick() }
                    .semantics { contentDescription = sosBtnCd },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(R.string.mesh_sos),
                    tint = Brick,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            CapChromeFontScale(maxScale = 1.3f) {
                Text(
                    text = stringResource(R.string.mesh_sos),
                    modifier = Modifier.testTag("talk_controls_sos_label"),
                    color = Brick,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun PttPerforatedButton(
    isPressed: Boolean,
    isVoxOpen: Boolean,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val isActive = isPressed || isVoxOpen

    val pulseTransition = rememberInfiniteTransition(label = "ptt")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 0.55f else 1f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "pttPulseAlpha"
    )

    val buttonBg = when {
        isPressed -> Pop
        isVoxOpen -> PopContainer
        else      -> PaperSunken
    }

    val rimColor = when {
        isPressed -> Pop.copy(alpha = pulseAlpha)
        isVoxOpen -> Pop.copy(alpha = pulseAlpha)
        else      -> InkHairline
    }

    val dotColor = when {
        isPressed -> PaperOnPop.copy(alpha = 0.25f)
        isVoxOpen -> Pop.copy(alpha = 0.2f)
        else      -> InkSecondary.copy(alpha = 0.16f)
    }

    val pttBtnCd = stringResource(R.string.cd_ptt_button)
    val pttActionLabel = stringResource(R.string.cd_ptt_action_label)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(buttonBg)
            .border(2.dp, rimColor, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    }
                )
            }
            .semantics {
                contentDescription = pttBtnCd
                onClick(label = pttActionLabel) {
                    onPressStart()
                    onPressEnd()
                    true
                }
            }
            .testTag("talk_controls_ptt"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stepX = 14.dp.toPx()
            val stepY = 10.dp.toPx()
            val dotRadius = 1.4.dp.toPx()
            val cols = (size.width / stepX).toInt()
            val rows = (size.height / stepY).toInt()

            for (col in 1..cols) {
                for (row in 1..rows) {
                    val x = col * stepX - (stepX / 2)
                    val y = row * stepY - (stepY / 2)
                    drawCircle(
                        color = dotColor,
                        radius = dotRadius,
                        center = Offset(x, y)
                    )
                }
            }
        }

        Surface(
            color = if (isPressed) Pop else PaperRaised.copy(alpha = 0.94f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, rimColor),
            modifier = Modifier.widthIn(min = 64.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isPressed) PaperOnPop else (if (isVoxOpen) Pop else Ink),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isPressed -> stringResource(R.string.talk_ptt_talking)
                        isVoxOpen -> stringResource(R.string.talk_ptt_vox_active)
                        else      -> stringResource(R.string.talk_ptt_hold)
                    },
                    color = if (isPressed) PaperOnPop else (if (isVoxOpen) Pop else Ink),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

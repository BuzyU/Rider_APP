package com.ridervoice.ui.screens.room

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .height(76.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Mic toggle button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 4.dp)
        ) {
            FilledIconButton(
                onClick = onToggleMic,
                modifier = Modifier
                    .size(52.dp)
                    .border(
                        1.5.dp,
                        if (isMicEnabled) ElectricCyan else BorderColor,
                        CircleShape
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isMicEnabled) DarkPalette.SurfaceAlt else Gunmetal
                )
            ) {
                Icon(
                    imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (isMicEnabled) "Microphone is on, tap to mute" else "Microphone muted, tap to turn on",
                    tint = if (isMicEnabled) ElectricCyan else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isMicEnabled) "MIC ON" else "MUTED",
                color = if (isMicEnabled) ElectricCyan else TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 2. Deafen toggle button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 6.dp)
        ) {
            FilledIconButton(
                onClick = onToggleDeafen,
                modifier = Modifier
                    .size(52.dp)
                    .border(
                        1.5.dp,
                        if (isDeafened) AlertRed else BorderColor,
                        CircleShape
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isDeafened) DarkPalette.EmergencyContainer else Gunmetal
                )
            ) {
                Icon(
                    imageVector = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isDeafened) "Audio deafened, tap to hear" else "Tap to deafen audio",
                    tint = if (isDeafened) AlertRed else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isDeafened) "DEAFENED" else "HEARING",
                color = if (isDeafened) AlertRed else TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 3. PTT Capsule Button (72dp height, gloved-hand friendly)
        PttPerforatedButton(
            isPressed = isPttPressed,
            isVoxOpen = isVoxOpen,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            onPressStart = onPttPressStart,
            onPressEnd = onPttPressEnd
        )

        // 4. Emergency SOS Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HazardContainer)
                    .border(2.dp, AlertRed, RoundedCornerShape(10.dp))
                    .clickable { onSosClick() }
                    .semantics { contentDescription = "Emergency SOS button" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "SOS",
                    tint = AlertRed,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "SOS",
                color = AlertRed,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
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
    val isDark = ThemeState.isDarkTheme
    val isActive = isPressed || isVoxOpen

    val pulseTransition = rememberInfiniteTransition(label = "ptt")
    val scale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pttScale"
    )

    val buttonBg = when {
        isPressed -> if (isDark) AmberContainer else LightPalette.AccentAmber
        isVoxOpen -> if (isDark) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
        else      -> if (isDark) DarkPalette.SurfaceAlt else LightPalette.Surface
    }

    val rimColor = when {
        isPressed -> NeonOrange
        isVoxOpen -> ElectricCyan
        else      -> BorderColor
    }

    val dotColor = when {
        isPressed -> if (isDark) NeonOrange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.6f)
        else      -> if (isDark) Color(0xFF101316) else Color(0xFF5A626E).copy(alpha = 0.25f)
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(buttonBg)
            .border(2.dp, rimColor, RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    }
                )
            }
            .semantics { contentDescription = "Push To Talk button" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepX = 14.dp.toPx()
            val stepY = 10.dp.toPx()
            val dotRadius = 1.5.dp.toPx()
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
            color = if (isPressed) NeonOrange else Gunmetal.copy(alpha = 0.9f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, rimColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isPressed) (if (isDark) Color.Black else Color.White) else (if (isActive) ElectricCyan else TextPrimary),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isPressed -> "TALKING"
                        isVoxOpen -> "VOX ACTIVE"
                        else      -> "HOLD TO TALK"
                    },
                    color = if (isPressed) (if (isDark) Color.Black else Color.White) else TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

package com.ridervoice.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.models.Participant
import com.ridervoice.ui.theme.*
import java.util.Locale

@Composable
fun ParticipantCard(
    participant: Participant,
    isFaded: Boolean = false,
    isSpeaking: Boolean = false,
    isCurrentUser: Boolean = false,
    isHost: Boolean = false,
    distanceMeters: Float? = null,
    speedKmh: Float? = null
) {
    val isDark = ThemeState.isDarkTheme
    val alphaVal = if (isFaded || participant.isGhost) 0.55f else 1.0f

    val pulse = rememberInfiniteTransition(label = "speakerPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val cardBorderColor by animateColorAsState(
        targetValue = when {
            isSpeaking -> ElectricCyan
            participant.isGhost -> AlertRed.copy(alpha = 0.6f)
            isCurrentUser -> ElectricCyan.copy(alpha = 0.4f)
            else -> BorderColor
        },
        label = "borderColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(alphaVal)
            .border(if (isSpeaking) 1.5.dp else 1.dp, cardBorderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSpeaking) {
                if (isDark) Color(0xFF14222D) else LightPalette.SurfaceAlt
            } else {
                DarkSlate
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSpeaking -> ElectricCyan.copy(alpha = pulseAlpha)
                                participant.isGhost -> AlertRed.copy(alpha = 0.25f)
                                isCurrentUser -> TechGreen.copy(alpha = 0.2f)
                                else -> Gunmetal
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isSpeaking -> ElectricCyan
                                participant.isGhost -> AlertRed
                                isCurrentUser -> TechGreen
                                else -> BorderColor
                            },
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSpeaking) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Speaking",
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (participant.isGhost) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = "Ghost",
                            tint = AlertRed,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        val initial = (participant.displayName ?: participant.identity).take(1).uppercase()
                        Text(
                            text = initial,
                            color = if (isCurrentUser) TechGreen else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val name = participant.displayName?.takeIf { it.isNotBlank() }
                            ?: if (isCurrentUser) "YOU" else participant.identity.take(12)

                        Text(
                            text = name.uppercase(),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        if (isCurrentUser) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = TechGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, TechGreen)
                            ) {
                                Text(
                                    text = "YOU",
                                    color = TechGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (isHost) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = NeonOrange.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, NeonOrange)
                            ) {
                                Text(
                                    text = "HOST",
                                    color = NeonOrange,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSpeaking) {
                            Text(
                                text = "TRANSMITTING LIVE",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (participant.isGhost) {
                            val elapsedSec = participant.disconnectedAt?.let {
                                ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
                            }
                            Text(
                                text = if (elapsedSec != null) "RECONNECTING (${elapsedSec}s)" else "RECONNECTING...",
                                color = NeonOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "CONNECTED ON SQUAD NET",
                                color = SuccessGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (!participant.isGhost && (distanceMeters != null || speedKmh != null)) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (distanceMeters != null && !isCurrentUser) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = "Distance",
                                tint = ElectricCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            val distStr = if (distanceMeters < 1000f) {
                                "${distanceMeters.toInt()}m"
                            } else {
                                String.format(Locale.US, "%.1f km", distanceMeters / 1000f)
                            }
                            Text(
                                text = distStr,
                                color = ElectricCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (speedKmh != null && speedKmh > 1f) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Speed",
                                tint = TextSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${speedKmh.toInt()} km/h",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

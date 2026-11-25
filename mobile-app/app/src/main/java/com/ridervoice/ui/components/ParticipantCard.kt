package com.ridervoice.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
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
    speedKmh: Float? = null,
    onComposed: (() -> Unit)? = null
) {
    if (onComposed != null) {
        androidx.compose.runtime.SideEffect(onComposed)
    }
    val alphaVal = if (isFaded || participant.isGhost) 0.58f else 1.0f
    val reduceMotion = rememberIsReduceMotionEnabled()

    val cardBorderColor by animateColorAsState(
        targetValue = when {
            isSpeaking -> Pop
            participant.isGhost -> Brick.copy(alpha = 0.7f)
            else -> InkHairline
        }
    )

    Card(
        modifier = Modifier
            .testTag("participant_card_${participant.identity}")
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(alphaVal)
            .border(1.5.dp, cardBorderColor, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSpeaking) PopContainer else PaperRaised
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
                                isSpeaking -> Color.Transparent
                                participant.isGhost -> BrickContainer
                                else -> PaperSunken
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isSpeaking -> Pop
                                participant.isGhost -> Brick
                                isCurrentUser -> Ink
                                else -> InkHairline
                            },
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSpeaking) {
                        SpeakingPulseLayer(
                            reduceMotion = reduceMotion,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                    if (isSpeaking) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = stringResource(R.string.cd_speaking),
                            tint = PaperOnPop,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (participant.isGhost) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = stringResource(R.string.cd_ghost),
                            tint = Brick,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        val resolvedName = ParticipantNameResolver.resolve(
                            identity = participant.identity,
                            livekitName = participant.displayName,
                            isCurrentUser = isCurrentUser
                        )
                        val initial = resolvedName.take(1).uppercase()
                        Text(
                            text = initial,
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val name = ParticipantNameResolver.resolve(
                            identity = participant.identity,
                            livekitName = participant.displayName,
                            isCurrentUser = isCurrentUser
                        )

                        Text(
                            text = name,
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .testTag("participant_name_${participant.identity}")
                        )

                        if (isCurrentUser) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = PaperSunken,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.75.dp, InkHairline)
                            ) {
                                Text(
                                    text = stringResource(R.string.badge_you_upper),
                                    color = Ink,
                                    style = RvTypography.microBadge,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (isHost) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = PopContainer,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.75.dp, Pop)
                            ) {
                                Text(
                                    text = stringResource(R.string.badge_host_upper),
                                    color = Pop,
                                    style = RvTypography.microBadge,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.heightIn(min = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSpeaking) {
                            Text(
                                text = stringResource(R.string.mesh_speaking),
                                color = Pop,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (participant.isGhost) {
                            val elapsedSec = participant.disconnectedAt?.let {
                                ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
                            }
                            Text(
                                text = if (elapsedSec != null) {
                                    stringResource(R.string.status_reconnecting_sec, elapsedSec)
                                } else {
                                    stringResource(R.string.ride_status_reconnecting)
                                },
                                color = Brick,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.ride_status_connected),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (!participant.isGhost && (distanceMeters != null || speedKmh != null)) {
                Column(
                    modifier = Modifier.widthIn(min = 56.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (distanceMeters != null && !isCurrentUser) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = stringResource(R.string.cd_distance),
                                tint = Ink,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            val distStr = if (distanceMeters < 1000f) {
                                stringResource(R.string.distance_m_value, distanceMeters.toInt())
                            } else {
                                stringResource(R.string.distance_km_value, String.format(Locale.US, "%.1f", distanceMeters / 1000f))
                            }
                            Text(
                                text = distStr,
                                modifier = Modifier.testTag("participant_distance_${participant.identity}"),
                                color = Ink,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (speedKmh != null && speedKmh > 1f) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = stringResource(R.string.cd_speed),
                                tint = InkSecondary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.speed_kmh_value, speedKmh.toInt()),
                                modifier = Modifier.testTag("participant_speed_${participant.identity}"),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakingPulseLayer(
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    if (reduceMotion) {
        Box(
            modifier = modifier
                .drawBehind { drawCircle(color = Pop) }
                .testTag("speaking_pulse_static")
        )
        return
    }
    val pulse = rememberInfiniteTransition(label = "speakerPulse")
    val pulseAlpha = pulse.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .drawBehind { drawCircle(color = Pop.copy(alpha = pulseAlpha.value)) }
            .testTag("speaking_pulse_active")
    )
}

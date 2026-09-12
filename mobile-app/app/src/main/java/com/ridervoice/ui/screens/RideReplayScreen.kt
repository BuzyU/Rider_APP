package com.ridervoice.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideReplayScreen(
    rideId: String,
    waypoints: List<RawWaypointEntity> = emptyList(),
    events: List<ConvoyEventEntity> = emptyList(),
    onBack: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme
    var timelineProgress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableIntStateOf(1) }

    val displayWaypoints = remember(waypoints) {
        if (waypoints.isNotEmpty()) {
            waypoints
        } else {

            List(30) { idx ->
                val progress = idx / 29f
                RawWaypointEntity(
                    id = "wpt-$idx",
                    sessionId = rideId,
                    lat = 18.74 + 0.05 * Math.sin(progress * Math.PI),
                    lng = 73.40 + 0.08 * progress,
                    speedMps = (18f + 12f * Math.sin(progress * Math.PI * 2).toFloat()).coerceAtLeast(0f),
                    heading = (45f + idx * 4f) % 360f,
                    timestamp = System.currentTimeMillis() - (30 - idx) * 60_000L
                )
            }
        }
    }

    LaunchedEffect(isPlaying, playbackSpeed) {
        if (isPlaying) {
            while (isPlaying) {
                delay(100L / playbackSpeed)
                timelineProgress += 0.01f * playbackSpeed
                if (timelineProgress >= 1f) {
                    timelineProgress = 0f
                    isPlaying = false
                }
            }
        }
    }

    val activeIndex = ((displayWaypoints.size - 1) * timelineProgress).toInt().coerceIn(0, displayWaypoints.size - 1)
    val currentWaypoint = displayWaypoints.getOrNull(activeIndex)
    val currentSpeedKmh = ((currentWaypoint?.speedMps ?: 0f) * 3.6f)
    val distanceKm = String.format("%.1f", (activeIndex / (displayWaypoints.size - 1).toFloat()) * 42.8f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "TELEMETRY REPLAY",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "SESSION: ${rideId.take(12).uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = GraphiteBase)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GraphiteBase)
        ) {

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSlate)
                    .border(1.5.dp, BorderColor, RoundedCornerShape(16.dp))
            ) {

                Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    val w = size.width
                    val h = size.height

                    val gridColor = if (isDark) Color(0x22FFFFFF) else Color(0x15000000)
                    for (i in 1..4) {
                        drawLine(gridColor, Offset(0f, h * i / 5), Offset(w, h * i / 5), strokeWidth = 1.dp.toPx())
                        drawLine(gridColor, Offset(w * i / 5, 0f), Offset(w * i / 5, h), strokeWidth = 1.dp.toPx())
                    }

                    if (displayWaypoints.size >= 2) {
                        val minLat = displayWaypoints.minOf { it.lat }
                        val maxLat = displayWaypoints.maxOf { it.lat }
                        val minLng = displayWaypoints.minOf { it.lng }
                        val maxLng = displayWaypoints.maxOf { it.lng }

                        val latSpan = (maxLat - minLat).coerceAtLeast(0.001)
                        val lngSpan = (maxLng - minLng).coerceAtLeast(0.001)

                        val fullPath = Path()
                        val activePath = Path()

                        displayWaypoints.forEachIndexed { index, wpt ->
                            val x = ((wpt.lng - minLng) / lngSpan * w).toFloat()
                            val y = (h - (wpt.lat - minLat) / latSpan * h).toFloat()
                            if (index == 0) {
                                fullPath.moveTo(x, y)
                                activePath.moveTo(x, y)
                            } else {
                                fullPath.lineTo(x, y)
                                if (index <= activeIndex) {
                                    activePath.lineTo(x, y)
                                }
                            }
                        }

                        drawPath(
                            path = fullPath,
                            color = TextSecondary.copy(alpha = 0.35f),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        drawPath(
                            path = activePath,
                            color = NeonOrange,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        val currentX = ((currentWaypoint!!.lng - minLng) / lngSpan * w).toFloat()
                        val currentY = (h - (currentWaypoint.lat - minLat) / latSpan * h).toFloat()

                        drawCircle(
                            color = ElectricCyan,
                            radius = 6.dp.toPx(),
                            center = Offset(currentX, currentY)
                        )
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.4f),
                            radius = 12.dp.toPx(),
                            center = Offset(currentX, currentY)
                        )
                    }
                }

                Surface(
                    color = Gunmetal.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SPEED",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "${currentSpeedKmh.toInt()} KM/H",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DISTANCE: $distanceKm KM",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontSize = 10.sp
                        )
                    }
                }

                Surface(
                    color = Gunmetal.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TechGreen))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GPS REPLAY ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Surface(
                color = DarkSlate,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TACTICAL TIMELINE",
                            color = NeonOrange,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${(timelineProgress * 100).toInt()}% COMPLETE",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = timelineProgress,
                        onValueChange = {
                            timelineProgress = it
                            isPlaying = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = NeonOrange,
                            activeTrackColor = NeonOrange,
                            inactiveTrackColor = Gunmetal
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { timelineProgress = (timelineProgress - 0.1f).coerceAtLeast(0f) }) {
                            Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = TextPrimary)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        FilledIconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.size(50.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = NeonOrange)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = if (isDark) Color.Black else Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(onClick = { timelineProgress = (timelineProgress + 0.1f).coerceAtMost(1f) }) {
                            Icon(Icons.Default.FastForward, contentDescription = "Forward", tint = TextPrimary)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Surface(
                            color = Gunmetal,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.clickable {
                                playbackSpeed = when (playbackSpeed) {
                                    1 -> 2
                                    2 -> 4
                                    else -> 1
                                }
                            }
                        ) {
                            Text(
                                text = "${playbackSpeed}X",
                                color = ElectricCyan,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "● 00:00 START RIDE",
                            color = TechGreen,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "▲ 14:32 HIGH SPEED",
                            color = WarningAmber,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "■ 28:10 CONVOY STOP",
                            color = AlertRed,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

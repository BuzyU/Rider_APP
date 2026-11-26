package com.ridervoice.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.services.RideRecorder
import com.ridervoice.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.*
import com.ridervoice.ui.theme.RvDateFormat
import com.ridervoice.ui.theme.rememberAppLocale
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideReplayScreen(
    rideId: String,
    waypoints: List<RawWaypointEntity> = emptyList(),
    events: List<ConvoyEventEntity> = emptyList(),
    onBack: () -> Unit,
    session: RideSessionEntity? = null,
    errorMessage: String? = null
) {
    val context = LocalContext.current
    var timelineProgress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableIntStateOf(1) }

    val hasTrack = waypoints.size >= 2

    // Genuine cumulative Haversine distances (km) per waypoint
    val cumulativeKm = remember(waypoints) {
        if (waypoints.isEmpty()) emptyList()
        else {
            val list = ArrayList<Float>(waypoints.size)
            var total = 0f
            list.add(0f)
            for (i in 1 until waypoints.size) {
                total += haversineKm(
                    waypoints[i - 1].lat, waypoints[i - 1].lng,
                    waypoints[i].lat, waypoints[i].lng
                )
                list.add(total)
            }
            list
        }
    }

    val totalDistanceKm = remember(session, cumulativeKm) {
        val sessionKm = (session?.totalDistanceMeters ?: 0f) / 1000f
        if (sessionKm > 0f) sessionKm else (cumulativeKm.lastOrNull() ?: 0f)
    }

    val startTimeMs = session?.startTime ?: waypoints.firstOrNull()?.timestamp
    val endTimeMs = session?.endTime ?: waypoints.lastOrNull()?.timestamp
    val durationSec = remember(startTimeMs, endTimeMs) {
        if (startTimeMs != null && endTimeMs != null && endTimeMs >= startTimeMs) {
            (endTimeMs - startTimeMs) / 1000L
        } else {
            0L
        }
    }

    val avgSpeedKmh = remember(totalDistanceKm, durationSec) {
        if (durationSec > 10L && totalDistanceKm > 0f) {
            totalDistanceKm / (durationSec / 3600f)
        } else {
            null
        }
    }

    val topSpeedKmh = remember(waypoints) {
        waypoints.maxOfOrNull { it.speedMps * 3.6f }?.takeIf { it > 0f }
    }

    val appLocale = rememberAppLocale()
    val defaultRideTitle = stringResource(R.string.replay_recorded_ride_default)
    val rideTitle = remember(session, defaultRideTitle) {
        session?.roomName?.trim()?.takeIf { it.isNotBlank() && !RideRecorder.isUuidLike(it) }
            ?: defaultRideTitle
    }

    val dateSubtitle = remember(startTimeMs, appLocale) {
        startTimeMs?.let {
            RvDateFormat.formatFullDateTime(it, appLocale)
        }
    }

    val durationText = remember(durationSec) {
        formatDuration(durationSec)
    }

    val distanceText = remember(totalDistanceKm, appLocale) {
        if (totalDistanceKm >= 10f) RvDateFormat.formatDecimal(totalDistanceKm, 1, appLocale)
        else RvDateFormat.formatDecimal(totalDistanceKm, 2, appLocale)
    }

    LaunchedEffect(isPlaying, playbackSpeed, hasTrack) {
        if (isPlaying && hasTrack) {
            while (isPlaying) {
                delay((100L / playbackSpeed).coerceAtLeast(25L))
                timelineProgress += 0.01f * playbackSpeed
                if (timelineProgress >= 1f) {
                    timelineProgress = 1f
                    isPlaying = false
                }
            }
        }
    }

    val activeIndex = if (waypoints.isNotEmpty()) {
        ((waypoints.size - 1) * timelineProgress).toInt().coerceIn(0, waypoints.size - 1)
    } else 0
    val currentWaypoint = waypoints.getOrNull(activeIndex)
    val currentSpeedKmh = ((currentWaypoint?.speedMps ?: 0f) * 3.6f)
    val currentTraveledKm = cumulativeKm.getOrNull(activeIndex) ?: 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .rvTopSafeArea()
            .rvBottomSafeArea()
    ) {
        // Editorial Top Bar (Circular Back + Centered Title + Circular Share)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(
                    imageVector = RvIcons.Back,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = Ink,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = stringResource(R.string.summary_ride_summary),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink
            )

            IconButton(
                onClick = {
                    val summaryLines = buildString {
                        append("RiderVoice · $rideTitle\n")
                        if (dateSubtitle != null) append("$dateSubtitle\n")
                        append("Distance: $distanceText km\n")
                        append("Duration: $durationText\n")
                        if (avgSpeedKmh != null) append("Avg Speed: ${RvDateFormat.formatDecimal(avgSpeedKmh, 0, appLocale)} km/h\n")
                        if (topSpeedKmh != null) append("Top Speed: ${RvDateFormat.formatDecimal(topSpeedKmh, 0, appLocale)} km/h")
                    }
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, summaryLines.trim())
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.cd_share_ride_summary)))
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(
                    imageVector = RvIcons.Share,
                    contentDescription = stringResource(R.string.cd_share_ride_summary),
                    tint = Ink,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Status Chips & Ride Header (100% real fetched data)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isCompleted = session == null || session.endTime != null
                    Surface(
                        color = if (isCompleted) MossContainer else PopContainer,
                        shape = RoundedCornerShape(RvRadius.pill)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isCompleted) Moss else Pop)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCompleted) stringResource(R.string.summary_status_completed) else stringResource(R.string.summary_status_in_progress),
                                color = if (isCompleted) Moss else Pop,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    if (session?.isSynced == true) {
                        Surface(
                            color = PaperSunken,
                            shape = RoundedCornerShape(RvRadius.pill),
                            border = BorderStroke(1.dp, InkHairline)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = RvIcons.CloudDone,
                                    contentDescription = null,
                                    tint = InkSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.replay_synced_badge),
                                    color = Ink,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (waypoints.isNotEmpty()) {
                        Surface(
                            color = PaperSunken,
                            shape = RoundedCornerShape(RvRadius.pill),
                            border = BorderStroke(1.dp, InkHairline)
                        ) {
                            Text(
                                text = stringResource(R.string.replay_gps_points_badge, waypoints.size),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    com.ridervoice.ui.components.InlineError(message = errorMessage)
                }

                Text(
                    text = rideTitle,
                    color = Ink,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (dateSubtitle != null) {
                    Text(
                        text = dateSubtitle,
                        color = InkSecondary,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            // Section Header: Convoy Telemetry
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.summary_section_telemetry),
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.9.sp
                )
                if (hasTrack) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = RvIcons.Verified,
                            contentDescription = null,
                            tint = Moss,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.replay_gps_track_logged),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 2x2 Telemetry Bento Grid (Real data only)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReplayTelemetryTile(
                        label = stringResource(R.string.summary_stat_distance),
                        value = distanceText,
                        unit = stringResource(R.string.unit_km),
                        icon = RvIcons.Straighten,
                        accentColor = Pop,
                        modifier = Modifier.weight(1f)
                    )
                    ReplayTelemetryTile(
                        label = stringResource(R.string.summary_stat_duration),
                        value = durationText,
                        unit = "",
                        icon = RvIcons.Schedule,
                        accentColor = Moss,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReplayTelemetryTile(
                        label = stringResource(R.string.summary_stat_avg_speed),
                        value = avgSpeedKmh?.let { RvDateFormat.formatDecimal(it, 0, appLocale) } ?: "--",
                        unit = if (avgSpeedKmh != null) stringResource(R.string.unit_kmh) else "",
                        icon = RvIcons.Speed,
                        accentColor = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    ReplayTelemetryTile(
                        label = stringResource(R.string.summary_stat_top_speed),
                        value = topSpeedKmh?.let { RvDateFormat.formatDecimal(it, 0, appLocale) } ?: "--",
                        unit = if (topSpeedKmh != null) stringResource(R.string.unit_kmh) else "",
                        icon = RvIcons.Bolt,
                        accentColor = Pop,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Section Header: Cartography & Route Replay
            Text(
                text = stringResource(R.string.replay_section_route),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.9.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (hasTrack) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Topographic Route Canvas Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(PaperSunken)
                                .border(1.dp, InkHairline, RoundedCornerShape(18.dp))
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp)
                            ) {
                                val gridColor = InkHairline
                                val inactiveTrackColor = InkSecondary.copy(alpha = 0.3f)
                                val activeTrackColor = Pop
                                val haloColor = PaperRaised
                                val startColor = Ink
                                val endColor = Moss
                                val w = size.width
                                val h = size.height

                                // Subtle cartographic grid lines
                                for (i in 1..4) {
                                    drawLine(gridColor, Offset(0f, h * i / 5f), Offset(w, h * i / 5f), strokeWidth = 1.dp.toPx())
                                    drawLine(gridColor, Offset(w * i / 5f, 0f), Offset(w * i / 5f, h), strokeWidth = 1.dp.toPx())
                                }

                                val minLat = waypoints.minOf { it.lat }
                                val maxLat = waypoints.maxOf { it.lat }
                                val minLng = waypoints.minOf { it.lng }
                                val maxLng = waypoints.maxOf { it.lng }

                                val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
                                val lngSpan = (maxLng - minLng).coerceAtLeast(0.0005)

                                val fullPath = Path()
                                val activePath = Path()
                                var startPt = Offset.Zero
                                var endPt = Offset.Zero
                                var activePt = Offset.Zero

                                waypoints.forEachIndexed { index, wpt ->
                                    val x = ((wpt.lng - minLng) / lngSpan * w).toFloat()
                                    val y = (h - (wpt.lat - minLat) / latSpan * h).toFloat()
                                    val pt = Offset(x, y)
                                    if (index == 0) {
                                        startPt = pt
                                        fullPath.moveTo(x, y)
                                        activePath.moveTo(x, y)
                                    } else {
                                        fullPath.lineTo(x, y)
                                        if (index <= activeIndex) {
                                            activePath.lineTo(x, y)
                                        }
                                    }
                                    if (index == waypoints.lastIndex) endPt = pt
                                    if (index == activeIndex) activePt = pt
                                }

                                // Full route outline
                                drawPath(
                                    path = fullPath,
                                    color = inactiveTrackColor,
                                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                // Warm paper casing under active route
                                drawPath(
                                    path = activePath,
                                    color = haloColor,
                                    style = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                // Active Ember route stroke
                                drawPath(
                                    path = activePath,
                                    color = activeTrackColor,
                                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )

                                // Start & End Pins
                                drawCircle(color = haloColor, radius = 6.5.dp.toPx(), center = startPt)
                                drawCircle(color = startColor, radius = 4.5.dp.toPx(), center = startPt)

                                drawCircle(color = haloColor, radius = 6.5.dp.toPx(), center = endPt)
                                drawCircle(color = endColor, radius = 4.5.dp.toPx(), center = endPt)

                                // Live Rider Playhead Marker
                                drawCircle(color = activeTrackColor.copy(alpha = 0.22f), radius = 13.dp.toPx(), center = activePt)
                                drawCircle(color = haloColor, radius = 7.dp.toPx(), center = activePt)
                                drawCircle(color = activeTrackColor, radius = 5.dp.toPx(), center = activePt)
                            }

                            // Top-Start Pill: Playback Status
                            Surface(
                                color = PaperRaised.copy(alpha = 0.94f),
                                shape = RoundedCornerShape(RvRadius.pill),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isPlaying) Pop else Moss)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isPlaying) stringResource(R.string.replay_status_playing) else stringResource(R.string.replay_status_gps_track),
                                        color = Ink,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Top-End Live Telemetry Pill (Genuine computed speed & distance)
                            Surface(
                                color = PaperRaised.copy(alpha = 0.94f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = stringResource(R.string.speed_kmh_value, currentSpeedKmh.toInt()),
                                        color = Pop,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.distance_km_value, RvDateFormat.formatDecimal(currentTraveledKm, 1, appLocale)),
                                        color = InkSecondary,
                                        style = RvTypography.microBadge
                                    )
                                }
                            }
                        }

                        // Real Speed Profile Sparkline (from actual waypoint speeds)
                        val speeds = remember(waypoints) { waypoints.map { (it.speedMps * 3.6f).coerceAtLeast(0f) } }
                        val maxSpeedPlot = remember(speeds) { (speeds.maxOrNull() ?: 1f).coerceAtLeast(10f) }
                        if (speeds.any { it > 0f }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.replay_speed_profile),
                                        color = InkSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.replay_peak_speed, maxSpeedPlot.toInt()),
                                        color = InkSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(PaperSunken.copy(alpha = 0.6f))
                                        .padding(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    val popColor = Pop
                                    val fillTop = Pop.copy(alpha = 0.22f)
                                    val fillBottom = Color.Transparent
                                    val cursorColor = Ink
                                    val w = size.width
                                    val h = size.height
                                    val strokePath = Path()
                                    val fillPath = Path()
                                    speeds.forEachIndexed { idx, spd ->
                                        val x = (idx.toFloat() / (speeds.size - 1).coerceAtLeast(1)) * w
                                        val y = h - (spd / maxSpeedPlot).coerceIn(0f, 1f) * h
                                        if (idx == 0) {
                                            strokePath.moveTo(x, y)
                                            fillPath.moveTo(x, h)
                                            fillPath.lineTo(x, y)
                                        } else {
                                            strokePath.lineTo(x, y)
                                            fillPath.lineTo(x, y)
                                        }
                                    }
                                    fillPath.lineTo(w, h)
                                    fillPath.close()

                                    drawPath(
                                        path = fillPath,
                                        brush = Brush.verticalGradient(listOf(fillTop, fillBottom))
                                    )
                                    drawPath(
                                        path = strokePath,
                                        color = popColor,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    val cx = timelineProgress.coerceIn(0f, 1f) * w
                                    drawLine(
                                        color = cursorColor.copy(alpha = 0.55f),
                                        start = Offset(cx, 0f),
                                        end = Offset(cx, h),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                }
                            }
                        }

                        // Timeline Scrub Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.replay_timeline),
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(timelineProgress * 100).toInt()}%",
                                    color = Ink,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = timelineProgress,
                                onValueChange = {
                                    timelineProgress = it
                                    isPlaying = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Pop,
                                    activeTrackColor = Pop,
                                    inactiveTrackColor = PaperSunken
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Playback Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { timelineProgress = (timelineProgress - 0.1f).coerceAtLeast(0f) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken)
                            ) {
                                Icon(RvIcons.FastRewind, contentDescription = stringResource(R.string.cd_rewind), tint = Ink, modifier = Modifier.rvRtlMirrored())
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            FilledIconButton(
                                onClick = {
                                    if (!isPlaying && timelineProgress >= 0.99f) {
                                        timelineProgress = 0f
                                    }
                                    isPlaying = !isPlaying
                                },
                                modifier = Modifier.size(54.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pop)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) RvIcons.Pause else RvIcons.PlayArrow,
                                    contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                                    tint = PaperOnPop,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            IconButton(
                                onClick = { timelineProgress = (timelineProgress + 0.1f).coerceAtMost(1f) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken)
                            ) {
                                Icon(RvIcons.FastForward, contentDescription = stringResource(R.string.cd_forward), tint = Ink, modifier = Modifier.rvRtlMirrored())
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Surface(
                                color = PaperSunken,
                                shape = RoundedCornerShape(RvRadius.pill),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(role = Role.Button) {
                                        playbackSpeed = when (playbackSpeed) {
                                            1 -> 2
                                            2 -> 4
                                            else -> 1
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = Pop,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Honest empty state when no GPS waypoints were stored
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(PaperSunken),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = RvIcons.Route,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.replay_no_gps_title),
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.replay_no_gps_desc),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Real Convoy Milestones / Events Section (ONLY shown when real events exist)
            if (events.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.replay_section_events),
                        color = InkSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.9.sp
                    )
                    Text(
                        text = pluralStringResource(R.plurals.events_logged_count, events.size, events.size),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    events.forEachIndexed { idx, event ->
                        ConvoyEventMilestoneCard(
                            index = idx + 1,
                            event = event,
                            rideStartMs = startTimeMs
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ReplayTelemetryTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = InkSecondary,
                    style = RvTypography.microBadge,
                    letterSpacing = 0.8.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = InkSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = Ink,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(RvRadius.pill))
                    .background(accentColor.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
private fun ConvoyEventMilestoneCard(
    index: Int,
    event: ConvoyEventEntity,
    rideStartMs: Long?
) {
    val appLocale = rememberAppLocale()
    val timeLabel = remember(event.timestamp, appLocale) {
        RvDateFormat.formatTimeOnly(event.timestamp, appLocale)
    }
    val elapsedLabel = remember(event.timestamp, rideStartMs) {
        if (rideStartMs != null && event.timestamp >= rideStartMs) {
            "+${formatDuration((event.timestamp - rideStartMs) / 1000L)}"
        } else null
    }
    val readableType = remember(event.eventType, appLocale) {
        event.eventType
            .replace('_', ' ')
            .lowercase(appLocale)
            .split(' ')
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase(appLocale) } }
    }
    val hasCoords = event.lat != 0.0 || event.lng != 0.0

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (index == 1) PopContainer else MossContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    color = if (index == 1) Pop else Moss,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = readableType,
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeLabel,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (elapsedLabel != null || hasCoords) {
                    Spacer(modifier = Modifier.height(3.dp))
                    val detail = buildList {
                        if (elapsedLabel != null) add(elapsedLabel)
                        if (hasCoords) add("${RvDateFormat.formatDecimal(event.lat, 4, appLocale)}, ${RvDateFormat.formatDecimal(event.lng, 4, appLocale)}")
                    }.joinToString(" · ")
                    Text(
                        text = detail,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationSec: Long): String {
    if (durationSec <= 0L) return "--"
    val hours = durationSec / 3600L
    val mins = (durationSec % 3600L) / 60L
    val secs = durationSec % 60L
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        mins > 0 -> "${mins}m"
        else -> "${secs}s"
    }
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (r * c).toFloat()
}

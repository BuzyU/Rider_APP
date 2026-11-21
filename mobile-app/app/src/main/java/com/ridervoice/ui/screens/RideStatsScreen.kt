package com.ridervoice.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.RideStatsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideStatsScreen(
    viewModel: RideStatsViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onReplayRide: (String) -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current

    val filterKeys = listOf("all", "month", "synced", "longest")
    var selectedFilter by remember { mutableStateOf("all") }

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val currentMonthKey = remember {
        SimpleDateFormat("MMMM yyyy", Locale.US).format(Date()).uppercase(Locale.US)
    }

    val filteredSessions = remember(uiState.sessions, selectedFilter) {
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
        when (selectedFilter) {
            "month" -> uiState.sessions.filter {
                monthFmt.format(Date(it.startTime)).uppercase(Locale.US) == currentMonthKey
            }
            "synced" -> uiState.sessions.filter { it.isSynced }
            "longest" -> uiState.sessions.sortedByDescending { it.totalDistanceMeters }
            else -> uiState.sessions
        }
    }

    val groupedByMonth = remember(filteredSessions) {
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
        filteredSessions.groupBy { session ->
            monthFmt.format(Date(session.startTime)).uppercase(Locale.US)
        }
    }

    val saddleTimeShort = remember(uiState.totalTime) {
        val parts = uiState.totalTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        // Top Header: "History" + Share Action + Profile Avatar
        CapChromeFontScale(maxScale = 1.3f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tab_history),
                    color = Ink,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            val shareIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "RiderVoice Ride Summary:\nRides: ${uiState.totalRidesCount}\nTotal Distance: ${uiState.totalDistance} km\nSaddle Time: $saddleTimeShort\nTop Speed: ${uiState.topSpeed} km/h\nAvg Speed: ${uiState.avgSpeed} km/h"
                                )
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.cd_share_ride_summary)))
                        },
                        modifier = Modifier
                            .size(RvTouchTarget.min)
                            .clip(CircleShape)
                            .background(PaperRaised)
                            .border(1.dp, InkHairline, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.cd_share_ride_stats),
                            tint = Ink,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Filter Pills Row ("All rides", "This month", "Synced", "Longest")
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterKeys) { filterKey ->
                val isSelected = filterKey == selectedFilter
                val filterLabel = when (filterKey) {
                    "month" -> stringResource(R.string.history_filter_month)
                    "synced" -> stringResource(R.string.history_filter_synced)
                    "longest" -> stringResource(R.string.history_filter_longest)
                    else -> stringResource(R.string.history_filter_all)
                }
                Surface(
                    color = if (isSelected) PopContainer else PaperSunken,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = BorderStroke(1.dp, if (isSelected) Pop.copy(alpha = 0.25f) else InkHairline),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .clickable { selectedFilter = filterKey }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filterLabel,
                            color = if (isSelected) Pop else InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Season Totals Bento Card with subtle topographic contour lines
            item {
                SeasonTotalsBentoCard(
                    year = currentYear,
                    ridesCount = uiState.totalRidesCount,
                    totalDistanceKm = uiState.totalDistance,
                    saddleTime = saddleTimeShort,
                    avgSpeed = uiState.avgSpeed,
                    topSpeed = uiState.topSpeed
                )
            }

            if (filteredSessions.isEmpty()) {
                item {
                    Surface(
                        color = PaperRaised,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                                    imageVector = Icons.Default.TwoWheeler,
                                    contentDescription = null,
                                    tint = InkSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            val activeFilterLabel = when (selectedFilter) {
                                "month" -> stringResource(R.string.history_filter_month)
                                "synced" -> stringResource(R.string.history_filter_synced)
                                "longest" -> stringResource(R.string.history_filter_longest)
                                else -> stringResource(R.string.history_filter_all)
                            }
                            Text(
                                text = if (uiState.sessions.isEmpty()) stringResource(R.string.history_empty_title) else stringResource(R.string.history_empty_filter, activeFilterLabel),
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.history_empty_desc),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                groupedByMonth.forEach { (monthLabel, monthSessions) ->
                    item(key = "header_$monthLabel") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = monthLabel,
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp
                            )
                            Text(
                                text = pluralStringResource(R.plurals.convoys_count, monthSessions.size, monthSessions.size),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    items(monthSessions, key = { it.id }) { session ->
                        RideHistoryCard(
                            session = session,
                            onClick = { onReplayRide(session.id) }
                        )
                    }
                }
            }

            // Bottom Field Journal / Replay Card (Matches Image 1 bottom footer tile)
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    color = PaperSunken.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReplayRide("latest") }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MossContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = stringResource(R.string.history_field_journal),
                                tint = Moss,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.history_field_journal),
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.history_field_journal_desc),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = InkSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SeasonTotalsBentoCard(
    year: Int,
    ridesCount: Int,
    totalDistanceKm: String,
    saddleTime: String,
    avgSpeed: String,
    topSpeed: String
) {
    val hairline = InkHairline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PaperSunken)
            .border(1.dp, InkHairline, RoundedCornerShape(24.dp))
    ) {
        // Subtle cartographic contour waves in the bottom-right corner
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            for (i in 0..2) {
                val offset = i * 16.dp.toPx()
                val path = Path().apply {
                    moveTo(w * 0.45f, h)
                    cubicTo(
                        w * 0.62f, h * 0.62f + offset,
                        w * 0.78f, h * 0.85f + offset,
                        w, h * 0.45f + offset
                    )
                }
                drawPath(
                    path = path,
                    color = hairline,
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_season_totals, year),
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.9.sp
                )
                Surface(
                    color = MossContainer,
                    shape = RoundedCornerShape(RvRadius.pill)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = Moss,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.history_active_fleet),
                            color = Moss,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SeasonStatCol(value = "$ridesCount", label = stringResource(R.string.history_stat_rides), modifier = Modifier.weight(0.8f))
                SeasonStatCol(value = totalDistanceKm, label = stringResource(R.string.history_stat_km_logged), modifier = Modifier.weight(1.1f))
                SeasonStatCol(value = saddleTime, label = stringResource(R.string.history_stat_saddle_time), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = InkHairline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.history_avg_speed, avgSpeed),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.history_top_speed, topSpeed),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SeasonStatCol(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            color = Ink,
            style = MaterialTheme.typography.displayLarge,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun RideHistoryCard(
    session: RideSessionEntity,
    onClick: () -> Unit
) {
    val dateLabel = remember(session.startTime) {
        SimpleDateFormat("EEE, d MMM · hh:mm a", Locale.US).format(Date(session.startTime))
    }
    val distKm = session.totalDistanceMeters / 1000f
    val distText = if (distKm >= 10f) {
        String.format(Locale.US, "%.0f km", distKm)
    } else {
        String.format(Locale.US, "%.1f km", distKm)
    }
    val durationSec = (((session.endTime ?: session.startTime) - session.startTime) / 1000L).coerceAtLeast(0L)
    val durHours = durationSec / 3600L
    val durMins = (durationSec % 3600L) / 60L
    val durText = when {
        durHours > 0 -> "${durHours}h ${durMins}m"
        durMins > 0 -> "${durMins}m"
        else -> "< 1m"
    }
    val avgKmh = if (durationSec > 30L) (distKm / (durationSec / 3600f)) else 0f
    val avgText = String.format(Locale.US, "Avg %.0f km/h", avgKmh)

    val initials = remember(session.roomName) {
        session.roomName.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifBlank { "RV" }
    }

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Stylized Mini Route Thumbnail (Matches Image 1)
            MiniRouteThumbnail(seed = session.id.hashCode())

            Spacer(modifier = Modifier.width(12.dp))

            // Middle: Title, Timestamp, Metric Pills
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.roomName,
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateLabel,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricPill(text = distText)
                    MetricPill(text = durText)
                }
                Spacer(modifier = Modifier.height(5.dp))
                MetricPill(text = avgText)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Badge + Chevron
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (session.isSynced) MossContainer else PopContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        color = if (session.isSynced) Moss else Pop,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.cd_open_replay),
                    tint = InkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricPill(text: String) {
    Surface(
        color = PaperSunken,
        shape = RoundedCornerShape(RvRadius.pill)
    ) {
        Text(
            text = text,
            color = Ink,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MiniRouteThumbnail(seed: Int) {
    val lineColor = Ink
    val popColor = Pop
    val variant = kotlin.math.abs(seed) % 3

    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaperSunken)
            .border(1.dp, InkHairline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            val start = Offset(w * 0.14f, h * 0.84f)
            val end = Offset(w * 0.86f, h * 0.16f)
            val path = Path().apply {
                moveTo(start.x, start.y)
                when (variant) {
                    0 -> cubicTo(w * 0.35f, h * 0.72f, w * 0.45f, h * 0.28f, end.x, end.y)
                    1 -> cubicTo(w * 0.18f, h * 0.36f, w * 0.72f, h * 0.62f, end.x, end.y)
                    else -> cubicTo(w * 0.45f, h * 0.85f, w * 0.55f, h * 0.15f, end.x, end.y)
                }
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawCircle(color = popColor, radius = 3.5.dp.toPx(), center = start)
            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = end)
        }
    }
}

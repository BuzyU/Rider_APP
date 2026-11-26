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
import com.ridervoice.services.RideRecorder
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.EmptyState
import com.ridervoice.ui.components.RvStateHost
import com.ridervoice.ui.components.UiLoadState
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.RideStatsViewModel
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import com.ridervoice.ui.theme.RvDateFormat
import com.ridervoice.ui.theme.rememberAppLocale
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideStatsScreen(
    viewModel: RideStatsViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onReplayRide: (String) -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val appLocale = rememberAppLocale()

    val filterKeys = listOf("all", "month", "synced", "longest")
    var selectedFilter by rememberSaveable { mutableStateOf("all") }

    val currentMonthBucket = remember {
        RvDateFormat.monthKey(System.currentTimeMillis())
    }
    val currentYear = remember(currentMonthBucket) { currentMonthBucket.year }

    val filteredSessions = remember(uiState.sessions, selectedFilter, currentMonthBucket) {
        when (selectedFilter) {
            "month" -> uiState.sessions.filter {
                RvDateFormat.monthKey(it.startTime) == currentMonthBucket
            }
            "synced" -> uiState.sessions.filter { it.isSynced }
            "longest" -> uiState.sessions.sortedByDescending { it.totalDistanceMeters }
            else -> uiState.sessions
        }
    }

    val groupedByMonth = remember(filteredSessions, appLocale) {
        filteredSessions.groupBy { session ->
            RvDateFormat.formatMonthYear(session.startTime, appLocale)
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
            .rvTopSafeArea()
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
                    style = MaterialTheme.typography.displayMedium,
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
                            imageVector = RvIcons.Share,
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
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterKeys, key = { it }) { filterKey ->
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
                    border = BorderStroke(1.dp, if (isSelected) Pop.copy(alpha = 0.25f) else InkBorderStrong),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { selectedFilter = filterKey }
                        )
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filterLabel,
                            color = if (isSelected) Pop else InkSecondary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val activeFilterLabel = when (selectedFilter) {
            "month" -> stringResource(R.string.history_filter_month)
            "synced" -> stringResource(R.string.history_filter_synced)
            "longest" -> stringResource(R.string.history_filter_longest)
            else -> stringResource(R.string.history_filter_all)
        }
        val historyLoadState: UiLoadState<List<RideSessionEntity>> = when {
            isLoading -> UiLoadState.Loading
            !errorMessage.isNullOrBlank() -> UiLoadState.Error(message = errorMessage, onRetry = onRetry)
            filteredSessions.isEmpty() -> UiLoadState.Empty(
                title = if (uiState.sessions.isEmpty()) stringResource(R.string.history_empty_title) else stringResource(R.string.history_empty_filter, activeFilterLabel),
                body = stringResource(R.string.history_empty_desc),
                icon = RvIcons.TwoWheeler
            )
            else -> UiLoadState.Content(filteredSessions)
        }

        RvStateHost(
            state = historyLoadState,
            modifier = Modifier.weight(1f),
            emptyContent = { empty ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                    item {
                        Surface(
                            color = PaperRaised,
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rv_state_empty")
                        ) {
                            EmptyState(
                                title = empty.title,
                                body = empty.body,
                                icon = empty.icon,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            color = PaperSunken.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onReplayRide("latest") }
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
                                        imageVector = RvIcons.Flag,
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
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.history_field_journal_desc),
                                        color = InkSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(
                                    imageVector = RvIcons.ChevronRight,
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
        ) {
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

                // Bottom Field Journal / Replay Card (Matches Image 1 bottom footer tile)
                item {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        color = PaperSunken.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onReplayRide("latest") }
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
                                    imageVector = RvIcons.Flag,
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.history_field_journal_desc),
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Icon(
                                imageVector = RvIcons.ChevronRight,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PaperSunken)
            .border(1.dp, InkHairline, RoundedCornerShape(24.dp))
    ) {
        // Subtle cartographic contour waves in the bottom-right corner
        Canvas(modifier = Modifier.matchParentSize()) {
            val hairline = InkHairline
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
                            imageVector = RvIcons.CheckCircleOutline,
                            contentDescription = null,
                            tint = Moss,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.history_active_fleet),
                            color = Moss,
                            style = MaterialTheme.typography.labelSmall,
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
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RideHistoryCard(
    session: RideSessionEntity,
    onClick: () -> Unit
) {
    val defaultRideTitle = stringResource(R.string.replay_recorded_ride_default)
    val displayTitle = remember(session.roomName, defaultRideTitle) {
        val trimmed = session.roomName.trim()
        if (trimmed.isBlank() || RideRecorder.isUuidLike(trimmed)) {
            defaultRideTitle
        } else {
            trimmed
        }
    }
    val appLocale = rememberAppLocale()
    val dateLabel = remember(session.startTime, appLocale) {
        RvDateFormat.formatShortDateTime(session.startTime, appLocale)
    }

    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Stylized Mini Route Thumbnail
            MiniRouteThumbnail(seed = session.id.hashCode())

            Spacer(modifier = Modifier.width(12.dp))

            // Middle: Title & Timestamp
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = dateLabel,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Chevron
            Icon(
                imageVector = RvIcons.ChevronRight,
                contentDescription = stringResource(R.string.cd_open_replay),
                tint = InkSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MiniRouteThumbnail(seed: Int) {
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
            val lineColor = Ink
            val popColor = Pop
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

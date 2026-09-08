package com.ridervoice.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.RideStatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideStatsScreen(
    viewModel: RideStatsViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onReplayRide: (String) -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme

    var selectedTab by remember { mutableStateOf("OVERVIEW") }
    val tabs = listOf("OVERVIEW", "SPEED", "ELEVATION")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TELEMETRY LOGBOOK",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "RIDE ANALYTICS",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
            // CRITICAL FIX: Wired Share Button!
            IconButton(onClick = {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "RiderVoice Telemetry Logbook:\nTotal Distance: ${uiState.totalDistance} km\nDuration: ${uiState.totalTime}\nTop Speed: ${uiState.topSpeed} km/h\nAvg Speed: ${uiState.avgSpeed} km/h"
                    )
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Ride Telemetry"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share Telemetry", tint = NeonOrange)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Tab Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    Surface(
                        color = if (isSelected) (if (isDark) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt) else DarkSlate,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) NeonOrange else BorderColor
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = tab }
                    ) {
                        Text(
                            text = tab,
                            color = if (isSelected) NeonOrange else TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(vertical = 10.dp)
                                .wrapContentWidth(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary Metrics Grid
            Surface(
                color = DarkSlate,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatItem(label = "TOTAL DISTANCE", value = uiState.totalDistance, unit = "KM")
                            Spacer(modifier = Modifier.height(20.dp))
                            StatItem(label = "AVERAGE SPEED", value = uiState.avgSpeed, unit = "KM/H")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            StatItem(label = "TOTAL RIDE TIME", value = uiState.totalTime, unit = "")
                            Spacer(modifier = Modifier.height(20.dp))
                            StatItem(label = "TOP SPEED", value = uiState.topSpeed, unit = "KM/H")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Chart Header & Replay CTA Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == "ELEVATION") "ELEVATION PROFILE (METERS)" else "VELOCITY OVER TIME (KM/H)",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp
                )

                // Replay Ride Button
                TextButton(
                    onClick = { onReplayRide("latest") },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REPLAY SESSION", color = ElectricCyan, style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic Chart Card (Functional Tab Filtering: Speed vs Elevation)
            Surface(
                color = DarkSlate,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 20.dp)
            ) {
                val isElevation = selectedTab == "ELEVATION"
                val rawPoints = if (isElevation) uiState.elevationDataPoints else uiState.speedDataPoints
                val maxY = if (isElevation) 1500f else 160f
                val chartColor = if (isElevation) TechGreen else NeonOrange

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Y Axis Labels
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        if (isElevation) {
                            Text("1500m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("1000m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("500m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("0m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                        } else {
                            Text("160", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("120", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("80", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("40", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("0", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Canvas Graph
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Canvas Grid lines & Polyline
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height

                                // Draw horizontal grid lines
                                for (i in 0..4) {
                                    val y = h * i / 4
                                    drawLine(
                                        color = if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                                        start = androidx.compose.ui.geometry.Offset(0f, y),
                                        end = androidx.compose.ui.geometry.Offset(w, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }

                                if (rawPoints.size >= 2) {
                                    val path = Path()
                                    rawPoints.forEachIndexed { index, value ->
                                        val x = (index.toFloat() / (rawPoints.size - 1)) * w
                                        val y = (h - (value / maxY * h)).coerceIn(0f, h)
                                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }
                                    drawPath(
                                        path = path,
                                        color = chartColor,
                                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                            }
                        }

                        // X Axis Labels
                        Row(
                            modifier = Modifier.fillMaxWidth().height(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text("0m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("15m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("30m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("45m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                            Text("60m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun StatItem(label: String, value: String, unit: String) {
    Column {
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = TextPrimary,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = NeonOrange,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

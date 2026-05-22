package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@Composable
fun RideStatsScreen(onBackClick: () -> Unit) {
    var selectedTab by remember { mutableStateOf("OVERVIEW") }
    val tabs = listOf("OVERVIEW", "SPEED", "ELEVATION")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GraphiteBase)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "RIDE STATS",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { /* Share */ }) {
                    Icon(Icons.Default.IosShare, contentDescription = "Share", tint = Color.White)
                }
            }

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSelected) NeonOrange else Gunmetal, RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0x33FF8A00) else Color.Transparent)
                            .padding(vertical = 8.dp)
                            .clickable { selectedTab = tab },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab,
                            color = if (isSelected) NeonOrange else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Stats Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    StatItem(label = "TOTAL DISTANCE", value = "124.7", unit = "km")
                    Spacer(modifier = Modifier.height(24.dp))
                    StatItem(label = "AVG SPEED", value = "68", unit = "km/h")
                }
                Column(modifier = Modifier.weight(1f)) {
                    StatItem(label = "TOTAL TIME", value = "02:35:18", unit = "")
                    Spacer(modifier = Modifier.height(24.dp))
                    StatItem(label = "TOP SPEED", value = "142", unit = "km/h")
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Chart Area
            Text(
                text = "SPEED OVER TIME",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 24.dp)
            ) {
                // Mockup of a chart
                Row(modifier = Modifier.fillMaxSize()) {
                    // Y Axis
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("160", color = TextSecondary, fontSize = 10.sp)
                        Text("120", color = TextSecondary, fontSize = 10.sp)
                        Text("80", color = TextSecondary, fontSize = 10.sp)
                        Text("40", color = TextSecondary, fontSize = 10.sp)
                        Text("0", color = TextSecondary, fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Graph content + X Axis
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        // Graph lines (mock)
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Grid lines
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                repeat(5) {
                                    Divider(color = Gunmetal, thickness = 1.dp)
                                }
                            }
                            // The actual graph line (simulated using Canvas)
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 16.dp)
                            ) {
                                val path = Path().apply {
                                    val width = size.width
                                    val height = size.height
                                    val points = listOf(
                                        0f to height,
                                        width * 0.2f to height * 0.4f,
                                        width * 0.4f to height * 0.5f,
                                        width * 0.6f to height * 0.2f,
                                        width * 0.8f to height * 0.3f,
                                        width to 0f
                                    )
                                    moveTo(points.first().first, points.first().second)
                                    for (i in 1 until points.size) {
                                        val p1 = points[i - 1]
                                        val p2 = points[i]
                                        // Simple cubic bezier for smooth curves
                                        val cp1x = (p1.first + p2.first) / 2
                                        cubicTo(
                                            cp1x, p1.second,
                                            cp1x, p2.second,
                                            p2.first, p2.second
                                        )
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = ElectricCyan,
                                    style = Stroke(width = 4.dp.toPx())
                                )
                            }
                        }

                        // X Axis
                        Row(
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text("0", color = TextSecondary, fontSize = 10.sp)
                            Text("30m", color = TextSecondary, fontSize = 10.sp)
                            Text("1h", color = TextSecondary, fontSize = 10.sp)
                            Text("1.5h", color = TextSecondary, fontSize = 10.sp)
                            Text("2h", color = TextSecondary, fontSize = 10.sp)
                            Text("2.5h", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        
        // Bottom Navigation Bar Overlay
        BottomNavBar(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun StatItem(label: String, value: String, unit: String) {
    Column {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

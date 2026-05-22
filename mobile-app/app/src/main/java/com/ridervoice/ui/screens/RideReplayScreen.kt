package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.ui.theme.DarkSlate
import com.ridervoice.ui.theme.GraphiteBase
import com.ridervoice.ui.theme.NeonOrange
import com.ridervoice.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideReplayScreen(
    rideId: String,
    waypoints: List<RawWaypointEntity>,
    events: List<ConvoyEventEntity>,
    onBack: () -> Unit
) {
    var timelineProgress by remember { mutableStateOf(0f) }

    val mapViewportState = rememberMapViewportState {
        if (waypoints.isNotEmpty()) {
            setCameraOptions {
                center(Point.fromLngLat(waypoints.first().lng, waypoints.first().lat))
                zoom(13.0)
                pitch(60.0) // Cinematic tilt
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FLIGHT REPLAY", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = NeonOrange) }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = GraphiteBase)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(GraphiteBase)) {
            // Map Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MapboxMap(
                    modifier = Modifier.fillMaxSize(),
                    mapViewportState = mapViewportState
                ) {
                    // TODO: Draw LineString from compressed GeoJSON using Mapbox extensions
                    // For now, if we had the Mapbox compose annotations plugin fully set up for Polylines,
                    // we would use PolylineAnnotation(points = points) here.
                }

                // Overlay
                Column(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Text("DISTANCE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("124.5 km", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }

            // Timeline Scrubber Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSlate, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(24.dp)
            ) {
                Text("TACTICAL TIMELINE", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                Slider(
                    value = timelineProgress,
                    onValueChange = { timelineProgress = it },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = NeonOrange, inactiveTrackColor = GraphiteBase),
                    modifier = Modifier.fillMaxWidth()
                )

                // Event List snippet
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("14:32 - HIGH SPEED ZONE", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("14:45 - REGROUP STOP", color = Color(0xFF34C759), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

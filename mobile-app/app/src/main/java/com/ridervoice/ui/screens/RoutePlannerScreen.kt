package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.geojson.Point
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.ridervoice.ui.viewmodels.RoutePlannerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    viewModel: RoutePlannerViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState().value
    var selectedPreference by remember { mutableStateOf("Scenic") }
    val preferences = listOf("Fastest", "Scenic", "Twisty", "Off-road")

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
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "ROUTE PLANNER",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(onClick = { /* Menu */ }) {
                Icon(Icons.Default.List, contentDescription = "Menu", tint = TextPrimary)
            }
        }

        // Input Fields
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            OutlinedTextField(
                value = uiState.origin,
                onValueChange = { viewModel.updateOrigin(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Origin", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = "Origin", tint = ElectricCyan) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = NeonOrange,
                    unfocusedBorderColor = Gunmetal,
                    containerColor = DarkSlate,
                    textColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.destination,
                onValueChange = { viewModel.updateDestination(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Destination", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = "Destination", tint = NeonOrange) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = NeonOrange,
                    unfocusedBorderColor = Gunmetal,
                    containerColor = DarkSlate,
                    textColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Route Preferences
        Text(
            text = "ROUTE PREFERENCE",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(preferences) { pref ->
                val isSelected = pref == selectedPreference
                Button(
                    onClick = { selectedPreference = pref },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) NeonOrange else DarkSlate
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = pref,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Map Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSlate)
                .border(1.dp, Gunmetal, RoundedCornerShape(16.dp))
        ) {
            val mapViewportState = rememberMapViewportState {
                setCameraOptions {
                    center(Point.fromLngLat(73.4069, 18.7481)) // Lonavala coords
                    zoom(11.0)
                }
            }
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = mapViewportState
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Nearby Riders
        if (uiState.nearbyRiders.isNotEmpty()) {
            Text(
                text = "NEARBY RIDERS",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.nearbyRiders) { rider ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSlate)
                            .clickable { viewModel.updateDestination(rider.handle) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Gunmetal),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(rider.handle.take(1).uppercase(), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("@${rider.handle}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${rider.distanceKm} km away", color = NeonOrange, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Bottom Stats & Action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = uiState.routeName,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("${uiState.distanceKm} km", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Text("DISTANCE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(uiState.duration, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Text("DURATION", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("${uiState.elevationGain} m", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Text("ELEV GAIN", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onBackClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
            ) {
                Text("SAVE ROUTE", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

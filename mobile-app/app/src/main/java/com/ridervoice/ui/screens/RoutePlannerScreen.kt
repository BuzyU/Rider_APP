package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(onBackClick: () -> Unit) {
    var origin by remember { mutableStateOf("Your Location") }
    var destination by remember { mutableStateOf("Lonavala, MH") }
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
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "ROUTE PLANNER",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(onClick = { /* Menu */ }) {
                Icon(Icons.Default.List, contentDescription = "Menu", tint = Color.White)
            }
        }

        // Input Fields
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            OutlinedTextField(
                value = origin,
                onValueChange = { origin = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Origin", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = "Origin", tint = ElectricCyan) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = NeonOrange,
                    unfocusedBorderColor = Gunmetal,
                    containerColor = DarkSlate,
                    textColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Destination", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = "Destination", tint = NeonOrange) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = NeonOrange,
                    unfocusedBorderColor = Gunmetal,
                    containerColor = DarkSlate,
                    textColor = Color.White
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
                        color = if (isSelected) Color.White else TextSecondary,
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

        // Bottom Stats & Action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "LONAVALA LOOP",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("123 km", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("DISTANCE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("02:40", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("DURATION", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("1420 m", color = Color.White, style = MaterialTheme.typography.titleLarge)
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
                Text("SAVE ROUTE", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

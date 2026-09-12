package com.ridervoice.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.RoutePlannerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    viewModel: RoutePlannerViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme

    var selectedPreference by remember { mutableStateOf("Scenic") }
    val preferences = listOf("Fastest", "Scenic", "Twisty", "Off-road")
    var showMenuDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {

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
                    text = "WAYPOINT PLOTTER",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "ROUTE PLANNER",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Box {
                IconButton(onClick = { showMenuDropdown = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Route Options", tint = TextPrimary)
                }
                DropdownMenu(
                    expanded = showMenuDropdown,
                    onDismissRequest = { showMenuDropdown = false },
                    modifier = Modifier.background(DarkSlate)
                ) {
                    DropdownMenuItem(
                        text = { Text("Export GPX Track", color = TextPrimary) },
                        onClick = {
                            showMenuDropdown = false
                            Toast.makeText(context, "Route exported as GPX to Downloads", Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = ElectricCyan) }
                    )
                    DropdownMenuItem(
                        text = { Text("Invert Route (Swap)", color = TextPrimary) },
                        onClick = {
                            showMenuDropdown = false
                            viewModel.swapOriginAndDestination()
                        },
                        leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, tint = NeonOrange) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Route", color = AlertRed) },
                        onClick = {
                            showMenuDropdown = false
                            viewModel.clearRoute()
                        },
                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = AlertRed) }
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = uiState.origin,
                        onValueChange = { viewModel.updateOrigin(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Origin / Starting Point", color = TextSecondary) },
                        leadingIcon = {
                            IconButton(onClick = { viewModel.useCurrentLocation() }) {
                                Icon(Icons.Default.MyLocation, contentDescription = "Use My GPS Location", tint = ElectricCyan)
                            }
                        },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            containerColor = DarkSlate,
                            textColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.destination,
                        onValueChange = { viewModel.updateDestination(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Destination / Mountain Pass", color = TextSecondary) },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = "Destination", tint = NeonOrange) },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            containerColor = DarkSlate,
                            textColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { viewModel.swapOriginAndDestination() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSlate)
                        .border(1.dp, BorderColor, CircleShape)
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap Origin and Destination", tint = NeonOrange)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ROUTING CHARACTERISTIC",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
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
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = pref.uppercase(),
                        color = if (isSelected) (if (isDark) Color.Black else Color.White) else TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSlate)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
        ) {
            val mapViewportState = rememberMapViewportState {
                setCameraOptions {
                    center(Point.fromLngLat(73.4069, 18.7481))
                    zoom(11.0)
                }
            }
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = mapViewportState
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.nearbyRiders.isNotEmpty()) {
            Text(
                text = "NEARBY ACTIVE SQUAD RIDERS",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.nearbyRiders) { rider ->
                    Surface(
                        color = DarkSlate,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        modifier = Modifier
                            .clickable { viewModel.setDestinationFromRider(rider) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Gunmetal),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = rider.handle.take(1).uppercase(),
                                    color = ElectricCyan,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("@${rider.handle}", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontSize = 13.sp)
                                Text("${rider.distanceKm} km away", color = NeonOrange, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = DarkSlate,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = uiState.routeName,
                    color = NeonOrange,
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("${uiState.distanceKm} KM", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("DISTANCE", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Column {
                        Text(uiState.duration, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("EST DURATION", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Column {
                        Text("${uiState.elevationGain} M", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("ELEVATION GAIN", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.saveRoute {
                            Toast.makeText(context, "Route '${uiState.routeName}' saved to logbook!", Toast.LENGTH_SHORT).show()
                            onBackClick()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = if (isDark) Color.Black else Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SAVE ROUTE TO LOGBOOK",
                        color = if (isDark) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

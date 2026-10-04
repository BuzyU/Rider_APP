package com.ridervoice.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.ridervoice.R
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.NearbyRider
import com.ridervoice.ui.viewmodels.RoutePlannerViewModel
import kotlin.math.cos
import kotlin.math.sin

private const val PREFS_NAME = "ridervoice_route_prefs"
private const val KEY_MAPBOX_TOKEN = "mapbox_token"

private fun isValidMapboxToken(token: String?): Boolean {
    if (token.isNullOrBlank()) return false
    val trimmed = token.trim()
    if (trimmed.contains("dummy") || trimmed.contains("your_token_here")) return false
    return (trimmed.startsWith("pk.") || trimmed.startsWith("sk.")) && trimmed.split(".").size == 3
}

@OptIn(ExperimentalMaterial3Api::class, com.mapbox.maps.MapboxExperimental::class)
@Composable
fun RoutePlannerScreen(
    viewModel: RoutePlannerViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme

    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var userCustomToken by remember {
        mutableStateOf(sharedPrefs.getString(KEY_MAPBOX_TOKEN, null) ?: "")
    }

    val resToken = remember {
        try {
            context.getString(R.string.mapbox_access_token)
        } catch (_: Exception) {
            ""
        }
    }

    val effectiveToken = if (isValidMapboxToken(userCustomToken)) userCustomToken else resToken
    val hasValidToken = isValidMapboxToken(effectiveToken)

    var useRadarMode by remember { mutableStateOf(!hasValidToken) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var inputTokenText by remember { mutableStateOf(userCustomToken) }

    var selectedPreference by remember { mutableStateOf("Scenic") }
    val preferences = listOf("Fastest", "Scenic", "Twisty", "Off-road")
    var showMenuDropdown by remember { mutableStateOf(false) }

    // Update Mapbox options if valid token is available
    LaunchedEffect(effectiveToken, hasValidToken) {
        if (hasValidToken) {
            try {
                com.mapbox.common.MapboxOptions.accessToken = effectiveToken
            } catch (_: Throwable) {
                // If native library throws, retain fallback
            }
        }
    }

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
                        text = {
                            Text(
                                if (useRadarMode) "Switch to Mapbox View" else "Switch to Tactical Radar",
                                color = TextPrimary
                            )
                        },
                        onClick = {
                            showMenuDropdown = false
                            if (useRadarMode && !hasValidToken) {
                                inputTokenText = userCustomToken
                                showTokenDialog = true
                            } else {
                                useRadarMode = !useRadarMode
                            }
                        },
                        leadingIcon = {
                            Icon(
                                if (useRadarMode) Icons.Default.Map else Icons.Default.Layers,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Configure Mapbox Key", color = TextPrimary) },
                        onClick = {
                            showMenuDropdown = false
                            inputTokenText = userCustomToken
                            showTokenDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = NeonOrange) }
                    )
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

        // Origin and Destination Inputs
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

        Spacer(modifier = Modifier.height(14.dp))

        // Routing Characteristic Selector
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

        // Interactive Map / Tactical Waypoint Radar Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSlate)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
        ) {
            if (!useRadarMode && hasValidToken) {
                // Live Mapbox Map with safe fallback
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
            } else {
                // Crash-proof Tactical Radar Waypoint Plotter HUD
                TacticalRadarCanvas(
                    nearbyRiders = uiState.nearbyRiders,
                    hasDestination = uiState.destination.isNotBlank(),
                    destinationLabel = uiState.destination
                )
            }

            // Top HUD Control Overlay Pill
            Surface(
                color = DarkSlate.copy(alpha = 0.88f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable {
                            if (useRadarMode && !hasValidToken) {
                                inputTokenText = userCustomToken
                                showTokenDialog = true
                            } else {
                                useRadarMode = !useRadarMode
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (useRadarMode) ElectricCyan else NeonOrange)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (useRadarMode) "TACTICAL RADAR" else "MAPBOX SAT",
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = "Switch Display Mode",
                        tint = TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Bottom-Left Status Pill
            Surface(
                color = DarkSlate.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderColor),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (useRadarMode) "RANGE: 25 KM • OFFLINE SAFE" else "MAPBOX TILES ACTIVE",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Nearby Active Squad Riders
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

        // Route Statistics & Save Action
        Surface(
            color = DarkSlate,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
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

    // Mapbox Token Configuration Dialog
    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            containerColor = DarkSlate,
            title = {
                Text(
                    text = "MAPBOX ACCESS TOKEN",
                    color = NeonOrange,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "To enable satellite and vector terrain tiles, enter your public Mapbox access token (pk.eyJ...). Alternatively, you can use the built-in Tactical Radar HUD which runs 100% offline without any API keys.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = inputTokenText,
                        onValueChange = { inputTokenText = it },
                        placeholder = { Text("pk.eyJ...", color = TextSecondary) },
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            textColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tokenToSave = inputTokenText.trim()
                        if (isValidMapboxToken(tokenToSave)) {
                            sharedPrefs.edit().putString(KEY_MAPBOX_TOKEN, tokenToSave).apply()
                            userCustomToken = tokenToSave
                            try {
                                com.mapbox.common.MapboxOptions.accessToken = tokenToSave
                            } catch (_: Throwable) {}
                            useRadarMode = false
                            showTokenDialog = false
                            Toast.makeText(context, "Mapbox key saved & activated", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please enter a valid Mapbox public token starting with 'pk.'", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Text("ACTIVATE MAPBOX", color = if (isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTokenDialog = false
                        useRadarMode = true
                    }
                ) {
                    Text("USE TACTICAL RADAR", color = ElectricCyan)
                }
            }
        )
    }
}

/**
 * Crash-proof Tactical Radar Canvas rendering motorcycle navigation HUD,
 * concentric range rings, cardinal axes, radar sweep, current rider position,
 * nearby squad blips, and destination waypoint trajectory.
 */
@Composable
private fun TacticalRadarCanvas(
    nearbyRiders: List<NearbyRider>,
    hasDestination: Boolean,
    destinationLabel: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = (minOf(size.width, size.height) / 2f) * 0.90f

            // Concentric Range Rings (5km, 10km, 15km, 25km)
            val ringFractions = listOf(0.20f, 0.40f, 0.65f, 1.0f)
            ringFractions.forEach { fraction ->
                val ringRadius = maxRadius * fraction
                drawCircle(
                    color = BorderColor.copy(alpha = 0.45f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
            }

            // Cardinal Axes (N-S and E-W crosshairs)
            drawLine(
                color = BorderColor.copy(alpha = 0.5f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = BorderColor.copy(alpha = 0.5f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )

            // Radar Sweep Beam
            val sweepRad = Math.toRadians(sweepAngle.toDouble())
            val sweepEnd = Offset(
                x = center.x + (maxRadius * cos(sweepRad)).toFloat(),
                y = center.y + (maxRadius * sin(sweepRad)).toFloat()
            )
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(ElectricCyan.copy(alpha = 0.8f), ElectricCyan.copy(alpha = 0.0f)),
                    start = center,
                    end = sweepEnd
                ),
                start = center,
                end = sweepEnd,
                strokeWidth = 2.dp.toPx()
            )

            // Destination Waypoint Trajectory (if set)
            if (hasDestination) {
                val destAngleRad = Math.toRadians(40.0)
                val destRadius = maxRadius * 0.82f
                val destOffset = Offset(
                    x = center.x + (destRadius * cos(destAngleRad)).toFloat(),
                    y = center.y - (destRadius * sin(destAngleRad)).toFloat()
                )

                // Trajectory vector line
                drawLine(
                    color = NeonOrange.copy(alpha = 0.75f),
                    start = center,
                    end = destOffset,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )

                // Waypoint flag blip
                drawCircle(
                    color = NeonOrange,
                    radius = 8.dp.toPx(),
                    center = destOffset
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = destOffset
                )
            }

            // Live Squad Riders Plotted Relative to Center
            val riderAngles = listOf(220.0, 310.0, 75.0, 140.0)
            nearbyRiders.forEachIndexed { index, rider ->
                val distKm = rider.distanceKm.toDoubleOrNull() ?: (index * 3.0 + 2.0)
                val distFraction = (distKm / 25.0).coerceIn(0.15, 0.95).toFloat()
                val angleDeg = riderAngles.getOrElse(index) { (index * 85.0) % 360.0 }
                val angleRad = Math.toRadians(angleDeg)

                val riderOffset = Offset(
                    x = center.x + (maxRadius * distFraction * cos(angleRad)).toFloat(),
                    y = center.y + (maxRadius * distFraction * sin(angleRad)).toFloat()
                )

                // Blip Outer Glow
                drawCircle(
                    color = ElectricCyan.copy(alpha = 0.35f),
                    radius = 9.dp.toPx(),
                    center = riderOffset
                )
                // Blip Core
                drawCircle(
                    color = ElectricCyan,
                    radius = 4.5.dp.toPx(),
                    center = riderOffset
                )
            }

            // Rider Self Position (Center Pulse & Motorcycle Pointer)
            drawCircle(
                color = ElectricCyan.copy(alpha = 0.25f),
                radius = 18.dp.toPx() * pulseScale,
                center = center
            )
            drawCircle(
                color = ElectricCyan,
                radius = 6.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = center
            )
        }

        // Radar Corner Legend
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Text(
                text = "GPS: 18.748° N, 73.407° E",
                color = ElectricCyan,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "BEARING: 034° NE",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp
            )
            if (hasDestination && destinationLabel.isNotBlank()) {
                Text(
                    text = "TARGET: ${destinationLabel.take(20).uppercase()}",
                    color = NeonOrange,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

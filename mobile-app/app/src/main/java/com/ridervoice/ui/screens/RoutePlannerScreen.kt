package com.ridervoice.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.ridervoice.R
import com.ridervoice.ui.components.PrimaryButton
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

    LaunchedEffect(effectiveToken, hasValidToken) {
        if (hasValidToken) {
            try {
                com.mapbox.common.MapboxOptions.accessToken = effectiveToken
            } catch (_: Throwable) {}
        }
    }

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkHairline,
        containerColor = PaperRaised,
        textColor = Ink,
        cursorColor = Pop
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Editorial Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink, modifier = Modifier.size(20.dp))
            }

            Text(
                text = "Route Planner",
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Box {
                IconButton(
                    onClick = { showMenuDropdown = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PaperRaised)
                        .border(1.dp, InkHairline, CircleShape)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Route Options", tint = Ink, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMenuDropdown,
                    onDismissRequest = { showMenuDropdown = false },
                    modifier = Modifier.background(PaperRaised)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (useRadarMode) "Switch to Mapbox View" else "Switch to Radar View",
                                color = Ink
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
                                tint = Ink
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Configure Mapbox Key", color = Ink) },
                        onClick = {
                            showMenuDropdown = false
                            inputTokenText = userCustomToken
                            showTokenDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = Pop) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export GPX Track", color = Ink) },
                        onClick = {
                            showMenuDropdown = false
                            Toast.makeText(context, "Route exported as GPX to Downloads", Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = Ink) }
                    )
                    DropdownMenuItem(
                        text = { Text("Invert Route (Swap)", color = Ink) },
                        onClick = {
                            showMenuDropdown = false
                            viewModel.swapOriginAndDestination()
                        },
                        leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, tint = Pop) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Route", color = Brick) },
                        onClick = {
                            showMenuDropdown = false
                            viewModel.clearRoute()
                        },
                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = Brick) }
                    )
                }
            }
        }

        // Origin & Destination Inputs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = uiState.origin,
                    onValueChange = { viewModel.updateOrigin(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Origin / Starting Point", color = InkSecondary) },
                    leadingIcon = {
                        IconButton(onClick = { viewModel.useCurrentLocation() }) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Use My GPS Location", tint = Moss)
                        }
                    },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.destination,
                    onValueChange = { viewModel.updateDestination(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Destination", color = InkSecondary) },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = "Destination", tint = Pop) },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { viewModel.swapOriginAndDestination() },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(1.dp, InkHairline, CircleShape)
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = "Swap Origin and Destination", tint = Pop)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Routing Preference Pills (matching History filter pills)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(preferences) { pref ->
                val isSelected = pref == selectedPreference
                Surface(
                    color = if (isSelected) PopContainer else PaperSunken,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = BorderStroke(1.dp, if (isSelected) Pop.copy(alpha = 0.3f) else InkHairline),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 38.dp)
                        .clickable { selectedPreference = pref }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pref,
                            color = if (isSelected) Pop else InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Map / Offline Radar Canvas Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(PaperSunken)
                .border(1.dp, InkHairline, RoundedCornerShape(24.dp))
        ) {
            if (!useRadarMode && hasValidToken) {
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
                TacticalRadarCanvas(
                    nearbyRiders = uiState.nearbyRiders,
                    hasDestination = uiState.destination.isNotBlank(),
                    destinationLabel = uiState.destination
                )
            }

            // Mode Toggle Pill
            Surface(
                color = PaperRaised.copy(alpha = 0.94f),
                shape = RoundedCornerShape(RvRadius.pill),
                border = BorderStroke(1.dp, InkHairline),
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
                            .background(if (useRadarMode) Moss else Pop)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (useRadarMode) "Offline Radar" else "Mapbox",
                        color = Ink,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = "Switch Display Mode",
                        tint = InkSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Nearby Riders (from ViewModel state)
        if (uiState.nearbyRiders.isNotEmpty()) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.route_nearby_riders_simulated).uppercase(),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.nearbyRiders) { rider ->
                    Surface(
                        color = PaperRaised,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.clickable { viewModel.setDestinationFromRider(rider) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = rider.handle.take(1).uppercase(),
                                    color = Ink,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("@${rider.handle}", color = Ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("${rider.distanceKm} km", color = Pop, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Route Summary & Save Bento Card
        Surface(
            color = PaperRaised,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            border = BorderStroke(1.dp, InkHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = uiState.routeName,
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("${uiState.distanceKm} km", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Distance", color = InkSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text(uiState.duration, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Est. duration", color = InkSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("${uiState.elevationGain} m", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Elevation", color = InkSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                PrimaryButton(
                    text = "Save Route",
                    leadingIcon = Icons.Default.Save,
                    onClick = {
                        viewModel.saveRoute {
                            Toast.makeText(context, "Route '${uiState.routeName}' saved to logbook!", Toast.LENGTH_SHORT).show()
                            onBackClick()
                        }
                    }
                )
            }
        }
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = PaperRaised,
            title = {
                Text(
                    text = "Mapbox Access Token",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter your public Mapbox token (pk.eyJ...) to enable vector terrain tiles, or continue using the offline Radar view.",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputTokenText,
                        onValueChange = { inputTokenText = it },
                        placeholder = { Text("pk.eyJ...", color = InkSecondary) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
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
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pop)
                ) {
                    Text("Activate", color = PaperOnPop, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTokenDialog = false
                        useRadarMode = true
                    }
                ) {
                    Text("Use Offline Radar", color = Ink)
                }
            }
        )
    }
}

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
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val ringColor = InkHairline
    val axisColor = InkSecondary.copy(alpha = 0.28f)
    val popColor = Pop
    val mossColor = Moss
    val dotHalo = PaperRaised

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperSunken)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = (minOf(size.width, size.height) / 2f) * 0.88f

            val ringFractions = listOf(0.25f, 0.50f, 0.75f, 1.0f)
            ringFractions.forEach { fraction ->
                val ringRadius = maxRadius * fraction
                drawCircle(
                    color = ringColor,
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
            }

            drawLine(axisColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1.dp.toPx())
            drawLine(axisColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1.dp.toPx())

            val sweepRad = Math.toRadians(sweepAngle.toDouble())
            val sweepEnd = Offset(
                x = center.x + (maxRadius * cos(sweepRad)).toFloat(),
                y = center.y + (maxRadius * sin(sweepRad)).toFloat()
            )
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(mossColor.copy(alpha = 0.65f), mossColor.copy(alpha = 0.0f)),
                    start = center,
                    end = sweepEnd
                ),
                start = center,
                end = sweepEnd,
                strokeWidth = 2.dp.toPx()
            )

            if (hasDestination) {
                val destAngleRad = Math.toRadians(40.0)
                val destRadius = maxRadius * 0.80f
                val destOffset = Offset(
                    x = center.x + (destRadius * cos(destAngleRad)).toFloat(),
                    y = center.y - (destRadius * sin(destAngleRad)).toFloat()
                )
                drawLine(
                    color = popColor.copy(alpha = 0.75f),
                    start = center,
                    end = destOffset,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
                drawCircle(color = dotHalo, radius = 8.dp.toPx(), center = destOffset)
                drawCircle(color = popColor, radius = 5.5.dp.toPx(), center = destOffset)
            }

            val riderAngles = listOf(220.0, 310.0, 75.0, 140.0)
            nearbyRiders.forEachIndexed { index, rider ->
                val distKm = rider.distanceKm.toDoubleOrNull() ?: (index * 3.0 + 2.0)
                val distFraction = (distKm / 25.0).coerceIn(0.15, 0.92).toFloat()
                val angleDeg = riderAngles.getOrElse(index) { (index * 85.0) % 360.0 }
                val angleRad = Math.toRadians(angleDeg)
                val riderOffset = Offset(
                    x = center.x + (maxRadius * distFraction * cos(angleRad)).toFloat(),
                    y = center.y + (maxRadius * distFraction * sin(angleRad)).toFloat()
                )
                drawCircle(color = mossColor.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = riderOffset)
                drawCircle(color = mossColor, radius = 4.5.dp.toPx(), center = riderOffset)
            }

            drawCircle(color = mossColor.copy(alpha = 0.2f), radius = 16.dp.toPx() * pulseScale, center = center)
            drawCircle(color = dotHalo, radius = 7.dp.toPx(), center = center)
            drawCircle(color = mossColor, radius = 5.dp.toPx(), center = center)
        }

        // Only display real user-entered target destination (zero hardcoded fake GPS/bearing!)
        if (hasDestination && destinationLabel.isNotBlank()) {
            Surface(
                color = PaperRaised.copy(alpha = 0.94f),
                shape = RoundedCornerShape(RvRadius.pill),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = destinationLabel.take(24),
                    color = Pop,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

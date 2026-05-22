package com.ridervoice.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.ridervoice.network.ConnectionState
import com.ridervoice.services.VoiceForegroundService
import com.ridervoice.state.RoomViewModel
import com.ridervoice.ui.components.ParticipantCard
import com.ridervoice.ui.theme.*

import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotation

@Composable
fun RoomScreen(
    roomName: String,
    userName: String,
    onLeave: () -> Unit,
    viewModel: RoomViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val participants by viewModel.participants.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val remoteLocations by viewModel.remoteLocations.collectAsState()

    var isVoiceChannelExpanded by remember { mutableStateOf(false) }

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            zoom(12.0)
            pitch(45.0)
        }
    }

    LaunchedEffect(roomName, userName) {
        val serviceIntent = Intent(context, VoiceForegroundService::class.java)
        context.startForegroundService(serviceIntent)
        viewModel.joinRoom(roomName, userName)
    }

    Box(modifier = Modifier.fillMaxSize().background(GraphiteBase)) {
        // Map Background
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasLocation) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = mapViewportState
            ) {
                remoteLocations.forEach { (_, location) ->
                    PointAnnotation(point = Point.fromLngLat(location.lng, location.lat))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(DarkSlate), contentAlignment = Alignment.Center) {
                Text("CONVOY MAP DISABLED", color = AlertRed, style = MaterialTheme.typography.titleLarge)
            }
        }

        // Top HUD Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Live Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AlertRed))
                Spacer(modifier = Modifier.width(8.dp))
                Text("LIVE", color = AlertRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            
            // Room Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(roomName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("${participants.size} Connected", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            }

            // Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = "Battery", tint = SuccessGreen)
                Spacer(modifier = Modifier.width(4.dp))
                Text("90%", color = SuccessGreen, fontWeight = FontWeight.Bold)
            }
        }

        // Speed Limit Sign
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, AlertRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("80", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text("km/h", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Bottom Voice Channel Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (isVoiceChannelExpanded) 0.85f else 0.3f)
                .background(DarkSlate, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(24.dp)
        ) {
            // Drag Handle / Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isVoiceChannelExpanded = !isVoiceChannelExpanded },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary))
            }

            if (isVoiceChannelExpanded) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = TextSecondary)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VOICE CHANNEL", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
                        Text(roomName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text("${participants.size} Online", color = SuccessGreen, style = MaterialTheme.typography.labelLarge)
                    }
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextSecondary)
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ALL", color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("NEARBY", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("LEADER", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Divider(color = NeonOrange, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).width(40.dp), thickness = 2.dp)

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(participants) { participant ->
                        ParticipantCard(participant = participant)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                // Collapsed View - Show Active Speaker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Gunmetal), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rahul", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Speaking", color = ElectricCyan, fontSize = 12.sp)
                    }
                    // Fake waveform
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(32.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.toggleMute() }) {
                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(if (muted) AlertRed else Gunmetal), contentAlignment = Alignment.Center) {
                        Icon(if (muted) Icons.Default.MicOff else Icons.Default.MicOff, contentDescription = "Mute", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("MUTE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Giant PTT
                Button(
                    onClick = { },
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PTT", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }

                // SOS
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onLeave() }) {
                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).border(2.dp, AlertRed, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Warning, contentDescription = "SOS", tint = AlertRed)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SOS", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

package com.ridervoice.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.audio.RouterState
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.DeviceSetupViewModel

@Composable
fun DeviceSetupScreen(
    convoyName: String,
    isHost: Boolean,
    viewModel: DeviceSetupViewModel = hiltViewModel(),
    onReady: () -> Unit,
    onBackClick: () -> Unit
) {
    val routerState by viewModel.routerState.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "DEVICE SETUP",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            if (routerState == RouterState.SCANNING || routerState == RouterState.CONNECTING_BT) {
                SpinningRadar()
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = NeonOrange,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(DarkSlate)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        val statusText = when (routerState) {
            RouterState.SCANNING -> "Scanning for devices..."
            RouterState.CONNECTING_BT -> "Connecting Bluetooth..."
            RouterState.ACTIVE -> "Handshake ✓"
            else -> "Ready"
        }
        val statusColor = if (routerState == RouterState.ACTIVE) SuccessGreen else ElectricCyan

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Active: ${activeDevice.displayName()}",
            color = TextSecondary,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        TacticalButton(
            text = if (convoyName == "GLOBAL") "Done" else "Start Ride",
            onClick = {
                viewModel.finishSetup()
                onReady()
            },
            enabled = true, // ALWAYS ENABLED to allow bypass
            color = NeonOrange,
            textColor = Color.White,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
fun SpinningRadar() {
    val infiniteTransition = rememberInfiniteTransition()
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val radius = (size.width / 2) * scale
        
        drawCircle(
            color = ElectricCyan.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )
        
        drawArc(
            color = ElectricCyan.copy(alpha = 0.5f),
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )
    }
}

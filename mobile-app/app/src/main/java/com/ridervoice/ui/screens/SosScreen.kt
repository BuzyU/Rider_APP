package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.ridervoice.ui.theme.*

@Composable
fun SosScreen(onCancelClick: () -> Unit) {
    var countdown by remember { mutableStateOf(15) }

    LaunchedEffect(key1 = countdown) {
        if (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AlertRed))
                Spacer(modifier = Modifier.width(8.dp))
                Text("LIVE", color = AlertRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            IconButton(onClick = onCancelClick) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("EMERGENCY", color = AlertRed, style = MaterialTheme.typography.displayLarge)
        
        Spacer(modifier = Modifier.weight(1f))

        // Countdown Circle
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(CircleShape)
                .border(8.dp, AlertRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SOS WILL BE SENT IN",
                    color = AlertRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = countdown.toString(),
                    color = AlertRed,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "SECONDS",
                    color = AlertRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Your location and ride details\nwill be shared with your contacts.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Send Button
        Button(
            onClick = { /* Send immediately */ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF4D4D)),
            border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SEND SOS NOW", color = AlertRed, style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Cancel Text Button
        TextButton(onClick = onCancelClick) {
            Text("CANCEL", color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

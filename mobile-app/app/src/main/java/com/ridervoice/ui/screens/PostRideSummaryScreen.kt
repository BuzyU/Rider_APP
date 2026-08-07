package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@Composable
fun PostRideSummaryScreen(
    durationMinutes: Int,
    distanceKm: Float,
    topSpeed: Float,
    onSaveToLogbook: (privacy: String) -> Unit,
    onDiscard: () -> Unit
) {
    var selectedPrivacy by remember { mutableStateOf("PRIVATE") } // PRIVATE, SQUAD

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlate.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(GraphiteBase, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("FLIGHT RECORDER", color = TextSecondary, fontSize = 12.sp, letterSpacing = 2.sp)
            Text("RIDE COMPLETE", color = NeonOrange, fontSize = 24.sp, fontWeight = FontWeight.Black)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Stats Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox("TIME", "${durationMinutes}m")
                StatBox("DISTANCE", "${"%.1f".format(distanceKm)}km")
                StatBox("TOP SPEED", "${"%.0f".format(topSpeed)}")
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Privacy Selection
            Text("VISIBILITY", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PrivacyChip("PRIVATE", selectedPrivacy == "PRIVATE") { selectedPrivacy = "PRIVATE" }
                PrivacyChip("SQUAD", selectedPrivacy == "SQUAD") { selectedPrivacy = "SQUAD" }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Actions
            Button(
                onClick = { onSaveToLogbook(selectedPrivacy) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("SAVE TO LOGBOOK", color = TextPrimary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onDiscard) {
                Text("DISCARD RECORDING", color = AlertRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
fun PrivacyChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) NeonOrange else DarkSlate
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, color = if (isSelected) TextPrimary else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

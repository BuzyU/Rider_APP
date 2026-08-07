package com.ridervoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ridervoice.ui.theme.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.audio.HardwarePTTManager

@Composable
fun TacticalDebugOverlay(
    hardwarePTTManager: HardwarePTTManager,
    modifier: Modifier = Modifier
) {
    val logs by hardwarePTTManager.debugLogs.collectAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(DarkSlate.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column {
            Text(
                text = "AVRCP / MEDIA SESSION TELEMETRY",
                color = Color.Green,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}

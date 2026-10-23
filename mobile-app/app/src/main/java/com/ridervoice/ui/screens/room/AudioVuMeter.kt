package com.ridervoice.ui.screens.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.state.RoomViewModel
import com.ridervoice.ui.theme.*

@Composable
fun AudioVuMeter(
    viewModel: RoomViewModel,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val amplitude by viewModel.currentAmplitude.collectAsState()
    val noiseFloor by viewModel.noiseFloor.collectAsState()
    val isDark = ThemeState.isDarkTheme
    val activeLevel = if (noiseFloor > 0 && isSpeaking) {
        ((amplitude / (noiseFloor * 4.5f)).coerceIn(0f, 1f) * 8).toInt()
    } else {
        0
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in 1..8) {
                val isLit = isSpeaking && i <= activeLevel
                val segmentColor = when {
                    !isLit -> if (isDark) Color(0xFF20252D) else LightPalette.SurfaceAlt
                    i <= 5 -> ElectricCyan
                    i <= 7 -> NeonOrange
                    else   -> AlertRed
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(segmentColor)
                        .border(0.5.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-20", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("-10", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("0", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
            Text("+3dB", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, color = TextSecondary)
        }
    }
}

package com.ridervoice.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.ridervoice.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.models.Participant


@Composable
fun ParticipantCard(participant: Participant, isFaded: Boolean = false) {
    val alphaVal = if (isFaded) 0.4f else 1.0f
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(alphaVal),
        colors = CardDefaults.cardColors(containerColor = DarkSlate)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = participant.identity.uppercase(), 
                    color = TextPrimary, 
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                
                if (participant.isGhost) {
                    Text("Reconnecting...", color = NeonOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Online", color = SuccessGreen, fontSize = 12.sp)
                }
            }
        }
    }
}

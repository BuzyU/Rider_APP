package com.ridervoice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridervoice.models.Participant

@Composable
fun ParticipantCard(participant: Participant) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(participant.identity)

            if (participant.isSpeaking) {
                Text("Speaking")
            }
        }
    }
}

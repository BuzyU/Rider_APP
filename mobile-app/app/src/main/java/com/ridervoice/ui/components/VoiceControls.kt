package com.ridervoice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VoiceControls(
    muted: Boolean,
    onMuteToggle: () -> Unit,
    onLeave: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {

        Button(onClick = onMuteToggle) {
            Text(if (muted) "Unmute" else "Mute")
        }

        Button(onClick = onLeave) {
            Text("Leave")
        }
    }
}

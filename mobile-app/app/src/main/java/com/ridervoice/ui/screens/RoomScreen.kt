package com.ridervoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ridervoice.models.Participant
import com.ridervoice.ui.components.ParticipantCard
import com.ridervoice.ui.components.VoiceControls

@Composable
fun RoomScreen() {

    var muted by remember {
        mutableStateOf(false)
    }

    val participants = remember {
        mutableStateListOf(
            Participant("Rider 1"),
            Participant("Rider 2"),
            Participant("Rider 3")
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(participants) {
                ParticipantCard(it)
            }
        }

        VoiceControls(
            muted = muted,
            onMuteToggle = {
                muted = !muted
            },
            onLeave = {}
        )
    }
}

package com.ridervoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun JoinRoomScreen(
    onJoin: (String) -> Unit
) {

    var roomCode by remember {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {

        OutlinedTextField(
            value = roomCode,
            onValueChange = {
                roomCode = it
            },
            label = {
                Text("Room Code")
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            onJoin(roomCode)
        }) {
            Text("Join")
        }
    }
}

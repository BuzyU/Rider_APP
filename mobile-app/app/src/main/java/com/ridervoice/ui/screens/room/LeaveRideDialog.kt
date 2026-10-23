package com.ridervoice.ui.screens.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridervoice.models.Participant
import com.ridervoice.ui.theme.*

@Composable
fun LeaveRideDialog(
    isHost: Boolean,
    otherRiders: List<Participant>,
    onDismiss: () -> Unit,
    onLeaveAsParticipant: () -> Unit,
    onTransferAndLeave: (successorId: String) -> Unit,
    onEndRideForEveryone: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme

    if (isHost) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "End or Leave Ride",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (otherRiders.isNotEmpty()) {
                        Text(
                            text = "You are the ride host with ${otherRiders.size} other rider(s) in this session. You can transfer hosting to another rider so they can continue, or end the ride for everyone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else {
                        Text(
                            text = "You are currently the only rider in this ride. Leaving will end the session.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (otherRiders.isNotEmpty()) {
                        Button(
                            onClick = {
                                val successor = otherRiders.first()
                                onTransferAndLeave(successor.identity)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "TRANSFER HOST & LEAVE",
                                color = if (isDark) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = onEndRideForEveryone,
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if (otherRiders.isNotEmpty()) "END RIDE FOR EVERYONE" else "END RIDE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("STAY IN RIDE", color = TextPrimary)
                    }
                }
            },
            dismissButton = {},
            containerColor = DarkSlate
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Leave Ride?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You will disconnect from group voice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = onLeaveAsParticipant,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LEAVE RIDE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("STAY IN RIDE", color = TextPrimary)
                }
            },
            containerColor = DarkSlate
        )
    }
}

package com.ridervoice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    onAddFriend: (handle: String) -> Unit
) {
    var friendHandle by remember { mutableStateOf("") }
    val isDark = ThemeState.isDarkTheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "COMMISSION SQUAD RIDER",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter callsign (@handle) to link transceivers and add to your trusted squad.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = friendHandle,
                    onValueChange = { friendHandle = it.trim().removePrefix("@") },
                    placeholder = { Text("e.g. ApexPredator", color = TextSecondary.copy(alpha = 0.5f)) },
                    leadingIcon = { Text("@", color = NeonOrange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp)) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (friendHandle.isNotBlank()) {
                        onAddFriend(friendHandle)
                    }
                    onDismiss()
                },
                enabled = friendHandle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "ADD TO SQUAD",
                    color = if (isDark) GraphiteBase else Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("CANCEL", color = TextPrimary)
            }
        },
        containerColor = DarkSlate,
        shape = RoundedCornerShape(12.dp)
    )
}

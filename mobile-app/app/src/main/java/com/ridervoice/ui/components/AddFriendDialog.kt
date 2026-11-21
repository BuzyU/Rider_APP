package com.ridervoice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    onAddFriend: (handle: String) -> Unit
) {
    var friendHandle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = PaperRaised,
        title = {
            Text(
                text = "Add Rider to Squad",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter a rider's handle (@handle) to add them to your squad.",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = friendHandle,
                    onValueChange = { friendHandle = it.trim().removePrefix("@") },
                    placeholder = { Text("e.g. ApexPredator", color = InkSecondary) },
                    leadingIcon = { Text("@", color = Pop, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp)) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Pop,
                        unfocusedBorderColor = InkHairline,
                        containerColor = PaperSunken.copy(alpha = 0.65f),
                        textColor = Ink,
                        cursorColor = Pop
                    ),
                    shape = RoundedCornerShape(14.dp),
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
                colors = ButtonDefaults.buttonColors(containerColor = Pop),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    "Add to Squad",
                    color = PaperOnPop,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Ink)
            }
        }
    )
}

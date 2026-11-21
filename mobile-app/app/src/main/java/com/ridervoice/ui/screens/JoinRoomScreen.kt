package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.ui.components.InlineError
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.RvScreen
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.JoinRoomViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinRoomScreen(
    initialToken: String? = null,
    initialRoomName: String = "",
    onBackClick: () -> Unit = {},
    onJoin: (roomName: String, userName: String) -> Unit,
    viewModel: JoinRoomViewModel = hiltViewModel()
) {
    val currentUser = remember { FirebaseAuth.getInstance().currentUser }
    var roomCode by remember { mutableStateOf(initialRoomName) }
    var userName by remember {
        mutableStateOf(currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "Rider")
    }

    val isTokenConnecting by viewModel.isConnecting.collectAsState()
    val tokenError by viewModel.error.collectAsState()

    var activeToken by remember { mutableStateOf(initialToken?.takeIf { it.isNotBlank() }) }
    LaunchedEffect(activeToken) {
        activeToken?.let { tok ->
            viewModel.joinViaToken(tok) { roomName ->
                onJoin(roomName, userName)
            }
        }
    }

    val canJoin = roomCode.isNotBlank() && userName.isNotBlank() && !isTokenConnecting

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkHairline,
        containerColor = PaperRaised,
        textColor = Ink,
        cursorColor = Pop
    )

    RvScreen(
        title = "Join a ride",
        onBack = onBackClick,
        bottomBar = if (activeToken == null) {
            {
                PrimaryButton(
                    text = if (isTokenConnecting) "Connecting..." else "Join ride",
                    onClick = {
                        if (canJoin) {
                            viewModel.smartJoin(roomCode.trim(), userName.trim()) { resolvedRoom ->
                                onJoin(resolvedRoom, userName.trim())
                            }
                        }
                    },
                    enabled = canJoin,
                    loading = isTokenConnecting
                )
            }
        } else null
    ) {
        if (activeToken != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isTokenConnecting) {
                        CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connecting to ride...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Verifying your invite link...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                    } else if (tokenError != null) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Brick,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Could not join ride",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Brick
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tokenError ?: "This join link may have expired or is invalid.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { activeToken = null },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Enter code", color = Ink, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    activeToken?.let { tok ->
                                        viewModel.joinViaToken(tok) { onJoin(it, userName) }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Pop),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Retry", color = PaperOnPop, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(RvSpacing.sm))

            Text(
                text = "Enter the ride code or paste an invite link from your convoy lead.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Your Callsign / Name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = InkSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it },
                label = { Text("Ride Code or Invite Link") },
                placeholder = { Text("e.g. LONAVALA-01 or paste link", color = InkSecondary) },
                leadingIcon = { Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = Pop) },
                supportingText = {
                    if (roomCode.isBlank()) {
                        Text(
                            "Enter a ride code, invite code, or invite URL",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors
            )

            if (tokenError != null && activeToken == null) {
                Spacer(modifier = Modifier.height(14.dp))
                InlineError(message = tokenError ?: "Could not join ride")
            }

            Spacer(modifier = Modifier.height(RvSpacing.xl))
        }
    }
}

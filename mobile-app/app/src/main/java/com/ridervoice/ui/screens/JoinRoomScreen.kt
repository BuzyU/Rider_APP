package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
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
    val isDark = ThemeState.isDarkTheme

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBackIosNew,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "ENTER RIDE CODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonOrange,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "JOIN RIDE",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            if (activeToken != null) {
                Spacer(modifier = Modifier.height(32.dp))
                Surface(
                    color = DarkSlate,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isTokenConnecting) {
                            CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "CONNECTING TO RIDE...",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Verifying ride invite...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else if (tokenError != null) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "COULD NOT JOIN RIDE",
                                style = MaterialTheme.typography.titleMedium,
                                color = AlertRed
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = tokenError ?: "This join link may have expired or is invalid.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        activeToken?.let { tok ->
                                            viewModel.joinViaToken(tok) { onJoin(it, userName) }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("RETRY", color = if (isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { activeToken = null },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("ENTER CODE", color = TextPrimary)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Enter the ride code or invite link provided by your ride leader to join directly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("Your Callsign / Name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = ElectricCyan) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = DarkSlate,
                        textColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it },
                    label = { Text("Ride Code or Invite Link") },
                    placeholder = { Text("e.g. LONAVALA-01 or paste invite link", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = NeonOrange) },
                    supportingText = {
                        if (roomCode.isBlank()) {
                            Text("Enter ride code, invite code, or paste invite link", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, fontSize = 11.sp)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = DarkSlate,
                        textColor = TextPrimary
                    )
                )

                if (tokenError != null && activeToken == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = AlertRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tokenError ?: "Could not join ride",
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val canJoin = roomCode.isNotBlank() && userName.isNotBlank() && !isTokenConnecting

                Button(
                    onClick = {
                        if (canJoin) {
                            viewModel.smartJoin(roomCode.trim(), userName.trim()) { resolvedRoom ->
                                onJoin(resolvedRoom, userName.trim())
                            }
                        }
                    },
                    enabled = canJoin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonOrange,
                        disabledContainerColor = Gunmetal
                    )
                ) {
                    if (isTokenConnecting) {
                        CircularProgressIndicator(
                            color = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "CONNECTING...",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isDark) Color.Black else Color.White
                        )
                    } else {
                        Text(
                            text = "JOIN RIDE",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = if (canJoin) (if (isDark) Color.Black else Color.White) else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

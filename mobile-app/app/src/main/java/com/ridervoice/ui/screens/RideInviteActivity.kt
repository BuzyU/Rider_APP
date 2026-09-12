package com.ridervoice.ui.screens

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.models.InviteRespondRequest
import com.ridervoice.models.JoinTokenRequest
import com.ridervoice.models.RideSession
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ApiService
import com.ridervoice.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RideInviteActivity : ComponentActivity() {

    @Inject lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val inviter = intent.getStringExtra("inviterHandle") ?: "@Unknown"
        val roomName = intent.getStringExtra("roomName") ?: "Convoy"
        val inviteId = intent.getStringExtra("inviteId") ?: ""

        setContent {
            var isJoining by remember { mutableStateOf(false) }
            var isDeclining by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GraphiteBase),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Gunmetal,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "INCOMING FREQUENCY DISPATCH",
                            color = NeonOrange,
                            fontSize = 11.sp,
                            fontFamily = SpaceMonoFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = inviter,
                        color = TextPrimary,
                        fontSize = 36.sp,
                        fontFamily = BarlowCondensedFamily,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = "requests you to tune into frequency",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontFamily = InterFamily,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = roomName,
                            color = ElectricCyan,
                            fontSize = 22.sp,
                            fontFamily = SpaceMonoFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AlertRed.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = AlertRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    color = AlertRed,
                                    fontSize = 13.sp,
                                    fontFamily = InterFamily,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    Button(
                        onClick = {
                            isJoining = true
                            errorMessage = null
                            scope.launch {
                                try {

                                    if (inviteId.isNotBlank()) {
                                        apiService.respondToInvite(InviteRespondRequest(inviteId, "ACCEPTED"))
                                    }

                                    val tokenRes = apiService.getJoinToken(JoinTokenRequest(roomName))
                                    if (tokenRes.isSuccessful && tokenRes.body() != null) {
                                        RideSession.livekitToken = tokenRes.body()!!.token
                                        RideSession.livekitUrl = tokenRes.body()!!.livekitUrl
                                        RideSession.activeRoomName = roomName
                                        RideSession.isHost = false
                                    }

                                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        putExtra("NAV_ROUTE", Routes.activeRideHudPath(roomName, inviter))
                                        putExtra("ROOM_NAME", roomName)
                                    }
                                    startActivity(launchIntent)
                                    finish()
                                } catch (e: Exception) {
                                    errorMessage = "Failed to connect: ${e.localizedMessage ?: "Network error"}. Please retry."
                                    isJoining = false
                                }
                            }
                        },
                        enabled = !isJoining && !isDeclining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TechGreen,
                            disabledContainerColor = TechGreen.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isJoining) {
                            CircularProgressIndicator(
                                color = if (ThemeState.isDarkTheme) GraphiteBase else Color.White,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Text(
                                "LOCK FREQUENCY (JOIN)",
                                color = if (ThemeState.isDarkTheme) GraphiteBase else Color.White,
                                fontSize = 18.sp,
                                fontFamily = BarlowCondensedFamily,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            isDeclining = true
                            scope.launch {
                                try {
                                    if (inviteId.isNotBlank()) {
                                        apiService.respondToInvite(InviteRespondRequest(inviteId, "DECLINED"))
                                    }
                                } catch (_: Exception) {}
                                finish()
                            }
                        },
                        enabled = !isJoining && !isDeclining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gunmetal,
                            disabledContainerColor = Gunmetal.copy(alpha = 0.5f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isDeclining) {
                            CircularProgressIndicator(
                                color = TextSecondary,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "DECLINE FREQUENCY",
                                color = TextSecondary,
                                fontSize = 16.sp,
                                fontFamily = BarlowCondensedFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

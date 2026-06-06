package com.ridervoice.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.DarkSlate
import com.ridervoice.ui.theme.GraphiteBase
import com.ridervoice.ui.theme.NeonOrange
import com.ridervoice.ui.theme.SuccessGreen

import com.ridervoice.network.ApiService
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
        
        // This wakes the screen even if locked
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val inviter = intent.getStringExtra("inviterHandle") ?: "@Unknown"
        val roomName = intent.getStringExtra("roomName") ?: "Convoy"

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GraphiteBase),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "TACTICAL INVITE",
                        color = NeonOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(
                        text = inviter,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black
                    )
                    
                    Text(
                        text = "is requesting you to join",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    Text(
                        text = roomName,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(64.dp))
                    
                    // Giant Glove-Friendly Buttons
                    Button(
                        onClick = {
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    // 1. Accept invite
                                    val inviteId = intent.getStringExtra("inviteId") ?: ""
                                    if (inviteId.isNotBlank()) {
                                        apiService.respondToInvite(com.ridervoice.models.InviteRespondRequest(inviteId, "ACCEPTED"))
                                    }

                                    // 2. Fetch token
                                    val tokenRes = apiService.getJoinToken(com.ridervoice.models.JoinTokenRequest(roomName))
                                    if (tokenRes.isSuccessful && tokenRes.body() != null) {
                                        com.ridervoice.models.RideSession.livekitToken = tokenRes.body()!!.token
                                    }

                                    // 3. Launch app
                                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    startActivity(launchIntent)
                                    finish()
                                } catch (e: Exception) {
                                    finish()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("JOIN RIDE", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { finish() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSlate),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("IGNORE", color = Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

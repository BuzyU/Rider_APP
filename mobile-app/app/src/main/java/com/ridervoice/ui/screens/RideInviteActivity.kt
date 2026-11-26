package com.ridervoice.ui.screens

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
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
    @Inject lateinit var activeRideRepository: com.ridervoice.state.ActiveRideRepository

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = com.ridervoice.security.SecurePreferences(newBase)
        val lang = prefs.getAppLanguage()
        val localized = com.ridervoice.utils.LocaleHelper.getLocalizedContext(newBase, lang)
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val inviter = intent.getStringExtra("inviterHandle") ?: "@Unknown"
        val roomName = intent.getStringExtra("roomName") ?: "Convoy"
        val inviteId = intent.getStringExtra("inviteId") ?: ""

        setContent {
            RiderVoiceTheme {
            var isJoining by remember { mutableStateOf(false) }
            var isDeclining by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Paper)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = PaperRaised,
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Surface(
                        shape = RoundedCornerShape(RvRadius.pill),
                        color = PopContainer,
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.invite_activity_badge),
                            color = Pop,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = inviter,
                        color = Ink,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.invite_activity_subtitle),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PaperSunken,
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = roomName,
                            color = Ink,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }

                    errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BrickContainer,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Brick),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = RvIcons.Warning,
                                    contentDescription = stringResource(R.string.cd_error),
                                    tint = Brick,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    color = Brick,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = {
                            isJoining = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val tokenRes = if (inviteId.isNotBlank()) {
                                        apiService.acceptRideInvite(inviteId)
                                    } else {
                                        apiService.getJoinToken(JoinTokenRequest(roomName))
                                    }
                                    val body = tokenRes.body()
                                    if (tokenRes.isSuccessful && body != null) {
                                        val resolvedRoomName = body.roomName ?: roomName
                                        activeRideRepository.enterLobby(
                                            roomId = body.roomId ?: resolvedRoomName,
                                            name = resolvedRoomName,
                                            isHost = false
                                        )
                                        activeRideRepository.applyTokenPayload(
                                            com.ridervoice.models.TokenPayload(
                                                token = body.token,
                                                livekitUrl = body.livekitUrl,
                                                roomId = body.roomId ?: resolvedRoomName,
                                                roomName = resolvedRoomName,
                                                diagnosticsEnabled = body.diagnosticsEnabled,
                                                testNumber = body.testNumber
                                            )
                                        )
                                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                            putExtra("NAV_ROUTE", Routes.lobbyPath(resolvedRoomName))
                                            putExtra("ROOM_NAME", resolvedRoomName)
                                        }
                                        if (launchIntent != null) {
                                            startActivity(launchIntent)
                                        }
                                        finish()
                                    } else {
                                        errorMessage = getString(R.string.invite_activity_accept_error, tokenRes.code())
                                        isJoining = false
                                    }
                                } catch (e: Exception) {
                                    errorMessage = getString(R.string.invite_activity_connect_error, e.localizedMessage ?: getString(R.string.error_network_generic))
                                    isJoining = false
                                }
                            }
                        },
                        enabled = !isJoining && !isDeclining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Pop,
                            disabledContainerColor = PaperSunken
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (isJoining) {
                            CircularProgressIndicator(
                                color = PaperOnPop,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Text(
                                stringResource(R.string.join_room_title),
                                color = PaperOnPop,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
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
                            .height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = PaperRaised,
                            contentColor = Ink
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkHairline),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isDeclining) {
                            CircularProgressIndicator(
                                color = InkSecondary,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                stringResource(R.string.action_decline),
                                color = InkSecondary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                }
            }
            }
        }
    }
}

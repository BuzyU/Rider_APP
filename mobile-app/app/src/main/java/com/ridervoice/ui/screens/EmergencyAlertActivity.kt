package com.ridervoice.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ridervoice.models.CancelAlertRequest
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.services.LocationService
import com.ridervoice.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EmergencyAlertActivity : ComponentActivity() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var locationService: LocationService

    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val alertType = intent.getStringExtra("alertType") ?: "CRASH SUSPECTED"
        val roomName = intent.getStringExtra("roomName") ?: "GLOBAL"

        startAlarmVibration()

        setContent {
            RiderVoiceTheme {
                EmergencyAlertContent(
                    alertType = alertType,
                    roomName = roomName,
                    onCall911 = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))
                        startActivity(dialIntent)
                    },
                    onTriggerDispatch = { rName, onResult ->
                        dispatchAlert(rName, onResult)
                    },
                    onCancelEmergency = { rName, alertId ->
                        cancelAlert(rName, alertId)
                    }
                )
            }
        }
    }

    private fun startAlarmVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAlarmVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }

    private fun dispatchAlert(roomName: String, onResult: (Boolean, String?) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loc = locationService.currentLocation.value
                val response = apiService.sendSosAlert(
                    SosAlertRequest(
                        roomName = roomName,
                        lat = loc?.latitude,
                        lng = loc?.longitude
                    )
                )
                if (response.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, "Dispatch error: ${response.code()}")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network failure")
            }
        }
    }

    private fun cancelAlert(roomName: String, alertId: String?) {
        stopAlarmVibration()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                apiService.cancelEmergencyAlert(
                    CancelAlertRequest(
                        roomName = roomName,
                        alertId = alertId,
                        reason = "FALSE_ALARM"
                    )
                )
            } catch (_: Exception) {}
            finish()
        }
    }

    override fun onDestroy() {
        stopAlarmVibration()
        super.onDestroy()
    }
}

@Composable
fun EmergencyAlertContent(
    alertType: String,
    roomName: String,
    onCall911: () -> Unit,
    onTriggerDispatch: (String, (Boolean, String?) -> Unit) -> Unit,
    onCancelEmergency: (String, String?) -> Unit
) {
    val isDark = ThemeState.isDarkTheme
    var secondsRemaining by remember { mutableIntStateOf(10) }
    var isDispatched by remember { mutableStateOf(false) }
    var isDispatching by remember { mutableStateOf(false) }
    var dispatchStatus by remember { mutableStateOf<String?>(null) }
    var isCancelling by remember { mutableStateOf(false) }

    LaunchedEffect(isDispatched) {
        if (!isDispatched) {
            while (secondsRemaining > 0) {
                delay(1000L)
                secondsRemaining--
            }

            isDispatching = true
            onTriggerDispatch(roomName) { success, err ->
                isDispatching = false
                isDispatched = true
                dispatchStatus = if (success) "DISPATCHED TO SQUAD & CONVOY" else "FAILED: $err"
            }
        }
    }

    val bgModifier = if (isDark) {
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0808))
    } else {
        Modifier
            .fillMaxSize()
            .background(LightPalette.Surface)
            .border(8.dp, AlertRed)
    }

    Box(modifier = bgModifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning Hazard",
                        tint = AlertRed,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EMERGENCY BEACON",
                        style = MaterialTheme.typography.titleMedium,
                        color = AlertRed,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = alertType.uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                    fontSize = 32.sp
                )

                Text(
                    text = "CHANNEL: $roomName",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                val progress = if (isDispatched) 0f else (secondsRemaining / 10f)
                val meterColor = AlertRed
                val trackColor = if (isDark) Color(0xFF331010) else LightPalette.SurfaceAlt

                Canvas(modifier = Modifier.fillMaxSize()) {

                    drawCircle(
                        color = trackColor,
                        style = Stroke(width = 10.dp.toPx())
                    )

                    drawArc(
                        color = meterColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isDispatched) {
                        Text(
                            text = "ALERT",
                            style = MaterialTheme.typography.displayLarge,
                            color = AlertRed,
                            fontSize = 36.sp
                        )
                        Text(
                            text = "SENT",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    } else {
                        Text(
                            text = String.format("%02d", secondsRemaining),
                            style = MaterialTheme.typography.displayLarge,
                            color = AlertRed,
                            fontSize = 54.sp
                        )
                        Text(
                            text = "SECONDS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = if (isDark) DarkPalette.Surface else LightPalette.SurfaceAlt,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusMessage = when {
                        isCancelling -> "CANCELING DISTRESS BROADCAST..."
                        isDispatching -> "TRANSMITTING DISTRESS TELEMETRY..."
                        isDispatched -> dispatchStatus ?: "EMERGENCY BROADCAST ACTIVE"
                        else -> "AUTOMATIC LOCATION DISPATCH IN ${secondsRemaining}S"
                    }
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDispatched) AlertRed else NeonOrange,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "GPS COORDS & CONVOY LINK BROADCAST VIA CELLULAR/SAT",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (!isDispatched) {
                    Button(
                        onClick = {
                            isDispatching = true
                            onTriggerDispatch(roomName) { success, err ->
                                isDispatching = false
                                isDispatched = true
                                dispatchStatus = if (success) "DISPATCHED TO SQUAD & CONVOY" else "FAILED: $err"
                            }
                        },
                        enabled = !isDispatching && !isCancelling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AlertRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isDispatching) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "SEND SOS IMMEDIATELY",
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                Button(
                    onClick = onCall911,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonOrange,
                        contentColor = if (isDark) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Call 911",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CALL 911 / EMERGENCY SERVICES",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 15.sp
                    )
                }

                Button(
                    onClick = {
                        isCancelling = true
                        onCancelEmergency(roomName, null)
                    },
                    enabled = !isCancelling,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) DarkPalette.SurfaceAlt else LightPalette.TextPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, BorderColor)
                ) {
                    if (isCancelling) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CANCELING...",
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        Text(
                            text = "■ I'M OKAY (CANCEL EMERGENCY)",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

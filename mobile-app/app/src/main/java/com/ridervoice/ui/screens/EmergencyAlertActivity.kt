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
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ridervoice.R
import com.ridervoice.models.CancelAlertRequest
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.services.LocationService
import com.ridervoice.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

@AndroidEntryPoint
class EmergencyAlertActivity : ComponentActivity() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var locationService: LocationService

    private var vibrator: Vibrator? = null
    private var incidentClientAlertId: String? = null
    @Volatile private var hasDispatchedIncident: Boolean = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        incidentClientAlertId?.let { outState.putString("incident_client_alert_id", it) }
        outState.putBoolean("incident_has_dispatched", hasDispatchedIncident)
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = com.ridervoice.security.SecurePreferences(newBase)
        val lang = prefs.getAppLanguage()
        val localized = com.ridervoice.utils.LocaleHelper.getLocalizedContext(newBase, lang)
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incidentClientAlertId = savedInstanceState?.getString("incident_client_alert_id")
            ?: java.util.UUID.randomUUID().toString()
        hasDispatchedIncident = savedInstanceState?.getBoolean("incident_has_dispatched", false) ?: false

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

        val alertType = intent.getStringExtra("alertType") ?: "CRASH SUSPECTED"
        val roomName = intent.getStringExtra("roomName") ?: "GLOBAL"

        startAlarmVibration()

        val emergencyNumber = com.ridervoice.utils.EmergencyNumbers.resolve(this).number

        setContent {
            RiderVoiceTheme {
                EmergencyAlertContent(
                    alertType = alertType,
                    roomName = roomName,
                    emergencyNumber = emergencyNumber,
                    onCall911 = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
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
        if (hasDispatchedIncident) {
            onResult(true, null)
            return
        }
        hasDispatchedIncident = true
        val clientAlertId = incidentClientAlertId ?: java.util.UUID.randomUUID().toString().also {
            incidentClientAlertId = it
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loc = locationService.currentLocation.value
                val response = apiService.sendSosAlert(
                    SosAlertRequest(
                        roomName = roomName,
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        clientAlertId = clientAlertId
                    )
                )
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        onResult(true, null)
                    } else {
                        hasDispatchedIncident = false
                        onResult(false, "Dispatch error: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                hasDispatchedIncident = false
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Network failure")
                }
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
    emergencyNumber: String,
    onCall911: () -> Unit,
    onTriggerDispatch: (String, (Boolean, String?) -> Unit) -> Unit,
    onCancelEmergency: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme
    var countdown by rememberSaveable { mutableStateOf(15) }
    var hasAutoDispatched by rememberSaveable { mutableStateOf(false) }
    var isDispatched by rememberSaveable { mutableStateOf(false) }
    var isDispatching by rememberSaveable { mutableStateOf(false) }
    var dispatchStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var isCancelling by rememberSaveable { mutableStateOf(false) }

    val triggerDispatchOnce: () -> Unit = {
        if (!hasAutoDispatched && !isDispatched && !isDispatching && !isCancelling) {
            hasAutoDispatched = true
            isDispatching = true
            onTriggerDispatch(roomName) { success, err ->
                isDispatching = false
                isDispatched = true
                dispatchStatus = if (success) {
                    context.getString(R.string.sos_dispatched_status)
                } else {
                    context.getString(R.string.sos_failed_prefix, err ?: context.getString(R.string.error_unknown))
                }
            }
        }
    }

    LaunchedEffect(hasAutoDispatched, isDispatched, isCancelling) {
        if (!hasAutoDispatched && !isDispatched && !isCancelling) {
            while (countdown > 0 && !hasAutoDispatched && !isDispatched && !isCancelling) {
                delay(1_000L)
                if (!hasAutoDispatched && !isDispatched && !isCancelling) {
                    countdown = (countdown - 1).coerceAtLeast(0)
                }
            }
            if (countdown <= 0 && !hasAutoDispatched && !isDispatched && !isCancelling) {
                triggerDispatchOnce()
            }
        }
    }

    val bgModifier = Modifier
        .fillMaxSize()
        .background(Paper)
        .border(6.dp, Brick)

    Box(modifier = bgModifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = BrickContainer,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brick.copy(alpha = 0.5f)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.cd_warning_hazard),
                            tint = Brick,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.sos_beacon_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Brick,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = alertType,
                    style = MaterialTheme.typography.displayMedium,
                    color = Ink,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.sos_channel_label, roomName),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center
            ) {
                val progress = if (isDispatched) 1f else (countdown.coerceIn(0, 15) / 15f)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val meterColor = Brick
                    val trackColor = PaperSunken
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
                            text = stringResource(R.string.sos_alert_upper),
                            style = MaterialTheme.typography.displayLarge,
                            color = Brick,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.sos_sent_upper),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ink
                        )
                    } else {
                        Text(
                            text = "${countdown}s",
                            style = RvTypography.displayHero,
                            color = Brick,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.sos_auto_dispatch_in, countdown),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Brick
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusMessage = when {
                        isCancelling -> stringResource(R.string.sos_canceling_broadcast)
                        isDispatching -> stringResource(R.string.sos_sending_alert)
                        isDispatched -> dispatchStatus ?: stringResource(R.string.sos_broadcast_active)
                        else -> stringResource(R.string.sos_auto_broadcast_hint, countdown)
                    }
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isDispatched) Brick else Pop,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.sos_gps_sent_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (!isDispatched) {
                    Button(
                        onClick = triggerDispatchOnce,
                        enabled = !isDispatching && !isCancelling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brick,
                            contentColor = PaperOnBrick
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (isDispatching) {
                            CircularProgressIndicator(
                                color = PaperOnBrick,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.sos_send_immediately),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Button(
                    onClick = onCall911,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Pop,
                        contentColor = PaperOnPop
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = stringResource(R.string.cd_call_number, emergencyNumber),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sos_call_emergency_slash, emergencyNumber),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
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
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurface,
                        contentColor = PaperOnPop
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    if (isCancelling) {
                        CircularProgressIndicator(
                            color = PaperOnPop,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sos_canceling),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.sos_cancel_emergency_btn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

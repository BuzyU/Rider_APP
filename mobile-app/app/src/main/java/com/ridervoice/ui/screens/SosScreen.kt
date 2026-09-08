package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.SosState
import kotlinx.coroutines.delay

@Composable
fun SosScreen(
    state: SosState,
    onCancelClick: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme
    var countdown by remember { mutableIntStateOf(15) }

    LaunchedEffect(countdown, state) {
        if (state is SosState.Idle && countdown > 0) {
            delay(1000L)
            countdown--
        } else if (countdown == 0 && state is SosState.Idle) {
            onSend()
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

    Box(modifier = bgModifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AlertRed))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EMERGENCY BEACON ACTIVE",
                        color = AlertRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                IconButton(onClick = onCancelClick) {
                    Icon(Icons.Default.Close, contentDescription = "Close SOS", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "EMERGENCY TRANSMIT",
                color = AlertRed,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 32.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Central State Handling
            when (state) {
                is SosState.Sent -> {
                    // CRITICAL FIX: Sent State Handled!
                    Surface(
                        color = if (isDark) DarkPalette.Surface else LightPalette.SurfaceAlt,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, TechGreen),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Alert Dispatched",
                                tint = TechGreen,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "DISTRESS ALERT BROADCAST",
                                style = MaterialTheme.typography.titleMedium,
                                color = TechGreen,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Live GPS coordinates and distress notification dispatched to your convoy and emergency squad.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is SosState.Sending -> {
                    Column(
                        modifier = Modifier.padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = AlertRed, strokeWidth = 4.dp, modifier = Modifier.size(54.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "TRANSMITTING DISTRESS SIGNAL...",
                            style = MaterialTheme.typography.labelLarge,
                            color = AlertRed
                        )
                    }
                }

                is SosState.Failed -> {
                    Surface(
                        color = HazardContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, AlertRed),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "TRANSMISSION FAILED",
                                style = MaterialTheme.typography.titleMedium,
                                color = AlertRed
                            )
                            Text(
                                text = state.message,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is SosState.Idle -> {
                    // Countdown Circle
                    Box(
                        modifier = Modifier.size(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val trackColor = if (isDark) Color(0xFF331010) else LightPalette.SurfaceAlt
                            drawCircle(
                                color = trackColor,
                                style = Stroke(width = 8.dp.toPx())
                            )
                            drawArc(
                                color = AlertRed,
                                startAngle = -90f,
                                sweepAngle = 360f * (countdown / 15f),
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "AUTOMATIC BROADCAST",
                                color = AlertRed,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp
                            )
                            Text(
                                text = String.format("%02d", countdown),
                                color = AlertRed,
                                style = MaterialTheme.typography.displayLarge,
                                fontSize = 72.sp
                            )
                            Text(
                                text = "SECONDS",
                                color = AlertRed,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons Console
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Call 911 Direct Dial Button
                Button(
                    onClick = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))
                        context.startActivity(dialIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonOrange,
                        contentColor = if (isDark) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = "Call 911", modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CALL 911 / EMERGENCY SERVICES",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Send SOS Now (if not yet sent)
                if (state !is SosState.Sent) {
                    Button(
                        onClick = onSend,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state is SosState.Failed) "RETRY SOS BROADCAST" else "SEND SOS NOW",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // CRITICAL FIX: Giant glove-friendly Cancel Bar replacing tiny text button!
                Button(
                    onClick = onCancelClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) DarkPalette.SurfaceAlt else LightPalette.TextPrimary,
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, BorderColor)
                ) {
                    Text(
                        text = if (state is SosState.Sent) "■ I'M SAFE NOW (DISARM ALERT)" else "■ I'M OKAY — CANCEL EMERGENCY",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

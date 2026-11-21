package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    val emergencyInfo = remember { com.ridervoice.utils.EmergencyNumbers.resolve(context) }
    val emergencyNumber = emergencyInfo.number
    val isDark = ThemeState.isDarkTheme
    var countdown by rememberSaveable { mutableStateOf(10) }
    var hasAutoDispatched by rememberSaveable { mutableStateOf(false) }
    var isCancelled by rememberSaveable { mutableStateOf(false) }

    val triggerDispatchOnce: () -> Unit = {
        if (state is SosState.Failed && !isCancelled) {
            onSend()
        } else if (state is SosState.Idle && !hasAutoDispatched && !isCancelled) {
            hasAutoDispatched = true
            onSend()
        }
    }

    LaunchedEffect(state, hasAutoDispatched, isCancelled) {
        if (state is SosState.Idle && !hasAutoDispatched && !isCancelled) {
            while (countdown > 0 && !hasAutoDispatched && !isCancelled) {
                delay(1_000L)
                if (!hasAutoDispatched && !isCancelled) {
                    countdown = (countdown - 1).coerceAtLeast(0)
                }
            }
            if (countdown <= 0 && !hasAutoDispatched && !isCancelled) {
                hasAutoDispatched = true
                onSend()
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
            .background(Paper)
            .border(6.dp, Brick)
    }

    Box(modifier = bgModifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = BrickContainer,
                    shape = RoundedCornerShape(RvRadius.pill),
                    border = BorderStroke(1.dp, Brick.copy(alpha = 0.5f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Brick)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "EMERGENCY BEACON",
                            color = Brick,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    }
                }

                IconButton(
                    onClick = {
                        isCancelled = true
                        onCancelClick()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PaperRaised)
                        .border(1.dp, InkHairline, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close SOS", tint = Ink, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Emergency Alert",
                color = Brick,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(18.dp))

            when (state) {
                is SosState.Sent -> {
                    Surface(
                        color = MossContainer,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, Moss),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Alert Sent",
                                tint = Moss,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (state.recipientsCount > 0) "Alert sent to ${state.recipientsCount} riders" else "Emergency alert sent",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Moss
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Live GPS coordinates and emergency notification sent to your ride group.",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is SosState.NotSent -> {
                    Surface(
                        color = BrickContainer,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, Brick),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Brick, modifier = Modifier.size(46.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "SOS Not Sent",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Brick
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Call ${state.emergencyNumber} immediately.",
                                color = Ink,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${state.emergencyNumber}"))
                                    context.startActivity(dialIntent)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Brick),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = PaperOnBrick)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Call ${state.emergencyNumber} Now",
                                    color = PaperOnBrick,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                is SosState.Sending -> {
                    Column(
                        modifier = Modifier.padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Brick, strokeWidth = 4.dp, modifier = Modifier.size(52.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Sending emergency alert...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Brick
                        )
                    }
                }

                is SosState.Failed -> {
                    Surface(
                        color = BrickContainer,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, Brick),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Brick, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Alert Failed to Send",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Brick
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message,
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is SosState.Idle -> {
                    Box(
                        modifier = Modifier.size(210.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val progress = (countdown.coerceIn(0, 10) / 10f)
                        val trackColor = if (isDark) Color(0xFF331010) else PaperSunken
                        val meterColor = Brick
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
                            Text(
                                text = "${countdown}s",
                                color = Brick,
                                style = MaterialTheme.typography.displayLarge,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "AUTO-SENDING IN ${countdown}s",
                                color = Brick,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
                        context.startActivity(dialIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Pop,
                        contentColor = PaperOnPop
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = "Call $emergencyNumber", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    val callLabel = if (emergencyInfo.isFallback) {
                        "Call $emergencyNumber (Emergency)"
                    } else {
                        "Call $emergencyNumber (${emergencyInfo.countryDisplay})"
                    }
                    Text(
                        text = callLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state !is SosState.Sent) {
                    Button(
                        onClick = triggerDispatchOnce,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brick)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = PaperOnBrick)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state is SosState.Failed) "Retry SOS Broadcast" else "Send SOS Now",
                            color = PaperOnBrick,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = {
                        isCancelled = true
                        onCancelClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurface,
                        contentColor = PaperOnPop
                    )
                ) {
                    Text(
                        text = if (state is SosState.Sent) "I'm safe now — Disarm alert" else "I'm okay — Cancel emergency",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

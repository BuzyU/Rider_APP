package com.ridervoice.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.ridervoice.ui.theme.*

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "alpha"
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 1000),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(1500)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GraphiteBase,
                        DarkSlate,
                        GraphiteBase
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            Text(
                text = "RIDER LINK",
                color = TextPrimary,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "STAY CONNECTED. RIDE UNITED.",
                color = NeonOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .alpha(alphaAnim)
        ) {
            Text(
                text = "INITIALIZING SYSTEMS...",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LinearProgressIndicator(
                color = NeonOrange,
                trackColor = Gunmetal,
                modifier = Modifier
                    .width(200.dp)
                    .height(2.dp)
            )
        }
    }
}

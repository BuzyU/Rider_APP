package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.theme.*

/**
 * Event-driven Roadside Paper splash screen (zero fixed delays, zero audio focus, zero drawings).
 * When [SplashSkipPolicy.shouldSkipSplash] returns true (including while res/raw/splash_ride.mp4 is
 * absent), navigation triggers immediately without starting a player.
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit,
    launchContext: SplashLaunchContext? = null,
    playerHost: SplashPlayerHost? = null
) {
    val context = LocalContext.current
    val resolvedContext = launchContext ?: remember(context) {
        SplashSkipPolicy.detectLaunchContext(context)
    }
    val shouldSkip = remember(resolvedContext) {
        SplashSkipPolicy.shouldSkipSplash(resolvedContext)
    }

    var hasFinished by remember { mutableStateOf(false) }
    val finishOnce: () -> Unit = {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    if (shouldSkip) {
        LaunchedEffect(Unit) {
            finishOnce()
        }
    } else {
        val activeHost = playerHost ?: NoOpSplashPlayerHost
        DisposableEffect(activeHost) {
            activeHost.start(
                onPrepared = {},
                onCompleted = { finishOnce() },
                onError = { finishOnce() }
            )
            onDispose {
                activeHost.release()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = Ink,
                style = RvTypography.displayMetric,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
        ) {
            Text(
                text = stringResource(R.string.splash_starting_up),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            val progressCd = stringResource(R.string.cd_splash_progress)
            LinearProgressIndicator(
                color = Pop,
                trackColor = PaperSunken,
                modifier = Modifier
                    .width(200.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(RvRadius.pill))
                    .semantics {
                        contentDescription = progressCd
                    }
            )
        }
    }
}

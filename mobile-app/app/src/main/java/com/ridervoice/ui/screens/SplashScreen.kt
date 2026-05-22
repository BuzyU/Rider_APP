package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ridervoice.ui.theme.GraphiteBase

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        onSplashFinished()
    }
    Box(modifier = Modifier.fillMaxSize().background(GraphiteBase), contentAlignment = Alignment.Center) {
        Text("INITIALIZING SYSTEMS...", color = Color.White)
    }
}

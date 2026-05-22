package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*

@Composable
fun LoginScreen(
    onGoogleSignInClick: () -> Unit,
    onPhoneOtpClick: () -> Unit,
    onGuestClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Logo Area (Simulating the Helmet Graphic)
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "RIDER LINK",
            color = Color.White,
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "STAY CONNECTED. RIDE UNITED.",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.weight(1f))

        // Center Welcome Text
        Text(
            text = "WELCOME RIDER",
            color = NeonOrange,
            style = MaterialTheme.typography.titleLarge,
            letterSpacing = 1.sp
        )
        Text(
            text = "Let's get you connected.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Action Buttons
        TacticalButton(
            text = "G  Continue with Google",
            onClick = onGoogleSignInClick,
            isOutlined = true,
            color = DarkSlate,
            textColor = Color.White
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TacticalButton(
            text = "📞  Continue with Phone",
            onClick = onPhoneOtpClick,
            isOutlined = true,
            color = DarkSlate,
            textColor = Color.White
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TacticalButton(
            text = "Continue as Guest",
            onClick = onGuestClick,
            isOutlined = true,
            color = Color(0xFF1D232B), // Very subtle outline
            textColor = TextSecondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Footer
        Text(
            text = "By continuing, you agree to our\nTerms of Service and Privacy Policy",
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.ridervoice.R
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onGoogleSignInClick: () -> Unit = {},
    onLoginSuccess: () -> Unit
) {
    val isLoading = viewModel.isLoading.collectAsState().value
    val loginSuccess = viewModel.loginSuccess.collectAsState().value
    val errorMessage = viewModel.errorMessage.collectAsState().value

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(loginSuccess) {
        if (loginSuccess) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    // Try to silently retrieve a saved Google credential on startup so users don't have to sign in again.
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    viewModel.handleGoogleIdToken(googleIdTokenCredential.idToken)
                }
            } catch (e: Exception) {
                // No saved credential available or retrieval failed; user can still tap the Google button.
                e.printStackTrace()
            }
        }
    }

    fun launchGoogleSignIn() {
        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    viewModel.handleGoogleIdToken(googleIdTokenCredential.idToken)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

        // Only show Google sign-in. Phone and Guest flows removed per request.
        TacticalButton(
            text = "G  Continue with Google",
            onClick = { launchGoogleSignIn() },
            isOutlined = true,
            color = DarkSlate,
            textColor = Color.White
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "By continuing, you agree to our\nTerms of Service and Privacy Policy",
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            style = MaterialTheme.typography.bodyLarge
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

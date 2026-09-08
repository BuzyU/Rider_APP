package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.ridervoice.R
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onGoogleSignInClick: () -> Unit = {},
    onPhoneOtpClick: () -> Unit = {},
    onLoginSuccess: () -> Unit,
    onRegisterClick: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val loginSuccess by viewModel.loginSuccess.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val resetEmailSent by viewModel.resetEmailSent.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showForgotPassword by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }

    // Intercept hardware/system back button when on password reset view
    BackHandler(enabled = showForgotPassword) {
        showForgotPassword = false
    }

    LaunchedEffect(loginSuccess) {
        if (loginSuccess) onLoginSuccess()
    }

    LaunchedEffect(resetEmailSent) {
        if (resetEmailSent) {
            snackbarHostState.showSnackbar("Password reset email sent! Check your inbox.")
            showForgotPassword = false
            viewModel.clearResetEmailSent()
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
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    viewModel.handleGoogleIdToken(googleIdTokenCredential.idToken)
                }
            } catch (e: NoCredentialException) {
                snackbarHostState.showSnackbar(
                    "No Google account found on this device. Please add a Google account in your device Settings."
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Google sign-in failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = GraphiteBase
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GraphiteBase)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Transceiver Brand Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Gunmetal,
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(BorderColor, BorderColor))),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "VHF / CB VOICE NETWORK",
                        color = ElectricCyan,
                        fontSize = 11.sp,
                        fontFamily = SpaceMonoFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Logo / Title
                Text(
                    text = "RIDERVOICE",
                    color = TextPrimary,
                    style = MaterialTheme.typography.displayLarge,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "STAY CONNECTED. RIDE UNITED.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = SpaceMonoFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Persistent Inline Error Banner
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    errorMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AlertRed.copy(alpha = 0.15f),
                            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(AlertRed, AlertRed))),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = AlertRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    color = AlertRed,
                                    fontSize = 13.sp,
                                    fontFamily = InterFamily,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.clearError() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss error",
                                        tint = AlertRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!showForgotPassword) {
                    // Header Subtext
                    Text(
                        text = "COMMUNICATIONS CONSOLE LOGIN",
                        color = NeonOrange,
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Tune in to access your squad frequencies.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                    )

                    // ── Email field ────────────────────────────────────────
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = { Text("Email", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            textColor = TextPrimary,
                            cursorColor = NeonOrange,
                            containerColor = Gunmetal
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ── Password field ─────────────────────────────────────
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = TextSecondary) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (email.isNotBlank() && password.isNotBlank()) {
                                    viewModel.signInWithEmail(email, password)
                                }
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            textColor = TextPrimary,
                            cursorColor = NeonOrange,
                            containerColor = Gunmetal
                        )
                    )

                    // ── Permanent Forgot password link (minimum 48dp touch target) ───
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                forgotEmail = email
                                showForgotPassword = true
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(
                                "Forgot Password?",
                                color = NeonOrange,
                                fontSize = 13.sp,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Sign In button (PTT-style bold action) ──────────────
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (email.isNotBlank() && password.isNotBlank()) {
                                viewModel.signInWithEmail(email, password)
                            }
                        },
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonOrange,
                            disabledContainerColor = DarkSlate
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = TextPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "SIGN IN TO TRANSCEIVER",
                                color = if (ThemeState.isDarkTheme) GraphiteBase else androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // ── Divider ────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Divider(modifier = Modifier.weight(1f), color = BorderColor)
                        Text(
                            "  SECONDARY CHANNELS  ",
                            color = TextSecondary,
                            fontFamily = SpaceMonoFamily,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                        Divider(modifier = Modifier.weight(1f), color = BorderColor)
                    }

                    // ── Google button ──────────────────────────────────────
                    OutlinedButton(
                        onClick = { if (!isLoading) launchGoogleSignIn() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Gunmetal,
                            contentColor = TextPrimary
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(BorderColor, BorderColor)))
                    ) {
                        Text(
                            text = "G   Continue with Google",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── Phone OTP & Guest Actions Row ──────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPhoneOtpClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Gunmetal,
                                contentColor = ElectricCyan
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(BorderColor, BorderColor)))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Phone OTP",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Phone SMS",
                                fontSize = 13.sp,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = { if (!isLoading) viewModel.signInAnonymously() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Gunmetal,
                                contentColor = TextPrimary
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(BorderColor, BorderColor)))
                        ) {
                            Text(
                                text = "Guest Rider",
                                fontSize = 13.sp,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── New sign up ────────────────────────────────────────
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            "Need a callsign? ",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontFamily = InterFamily
                        )
                        TextButton(
                            onClick = onRegisterClick,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(
                                "Register New Account",
                                color = NeonOrange,
                                fontSize = 14.sp,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // ── Forgot Password flow ───────────────────────────────
                    Text(
                        text = "RESET FREQUENCY KEY",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Enter your registered email address to receive password reset instructions.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        fontFamily = InterFamily,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                    )

                    OutlinedTextField(
                        value = forgotEmail,
                        onValueChange = { forgotEmail = it.trim() },
                        label = { Text("Registered Email", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (forgotEmail.isNotBlank()) viewModel.sendPasswordReset(forgotEmail)
                            }
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = BorderColor,
                            textColor = TextPrimary,
                            cursorColor = NeonOrange,
                            containerColor = Gunmetal
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (forgotEmail.isNotBlank()) viewModel.sendPasswordReset(forgotEmail)
                        },
                        enabled = !isLoading && forgotEmail.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = TextPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "DISPATCH RESET LINK",
                                color = if (ThemeState.isDarkTheme) GraphiteBase else androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    TextButton(
                        onClick = { showForgotPassword = false },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            "← Return to Sign In",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "By continuing, you agree to our\nTerms of Service and Privacy Policy",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = SpaceMonoFamily,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

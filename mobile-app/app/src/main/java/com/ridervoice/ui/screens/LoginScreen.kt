package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
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

    BackHandler(enabled = showForgotPassword) {
        showForgotPassword = false
    }

    LaunchedEffect(loginSuccess) {
        if (loginSuccess) onLoginSuccess()
    }

    LaunchedEffect(resetEmailSent) {
        if (resetEmailSent) {
            snackbarHostState.showSnackbar(context.getString(R.string.auth_reset_email_sent))
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
                    context.getString(R.string.auth_no_google_account)
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.auth_google_failed, e.localizedMessage ?: context.getString(R.string.error_unknown))
                )
            }
        }
    }

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkHairline,
        textColor = Ink,
        cursorColor = Pop,
        containerColor = PaperSunken.copy(alpha = 0.5f)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Paper,
        contentWindowInsets = ZeroWindowInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Paper)
                .statusBarsPadding()
                .navigationBarsPadding()
                .rvHorizontalSafeArea()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Ink,
                style = RvTypography.displayMetric,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.app_tagline),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                errorMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BrickContainer,
                        border = BorderStroke(1.dp, Brick),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = stringResource(R.string.cd_error),
                                tint = Brick,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = msg,
                                color = Brick,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_dismiss_error),
                                    tint = Brick,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Main Auth Bento Card
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!showForgotPassword) {
                        Text(
                            text = stringResource(R.string.auth_sign_in_title),
                            color = Ink,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.auth_sign_in_subtitle),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.trim() },
                            label = { Text(stringResource(R.string.auth_email_label), color = InkSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            colors = fieldColors
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.auth_password_label), color = InkSecondary) },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
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
                                        contentDescription = if (passwordVisible) stringResource(R.string.cd_hide_password) else stringResource(R.string.cd_show_password),
                                        tint = InkSecondary
                                    )
                                }
                            },
                            colors = fieldColors
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
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
                                    stringResource(R.string.auth_forgot_password),
                                    color = Pop,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

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
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Pop,
                                contentColor = PaperOnPop,
                                disabledContainerColor = PaperSunken,
                                disabledContentColor = InkSecondary
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = PaperOnPop,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    stringResource(R.string.action_sign_in),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Divider(modifier = Modifier.weight(1f), color = InkHairline)
                            Text(
                                stringResource(R.string.auth_or_continue_with),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Divider(modifier = Modifier.weight(1f), color = InkHairline)
                        }

                        OutlinedButton(
                            onClick = {
                                if (!isLoading) {
                                    onGoogleSignInClick()
                                    launchGoogleSignIn()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = PaperSunken.copy(alpha = 0.55f),
                                contentColor = Ink
                            ),
                            border = BorderStroke(1.dp, InkHairline)
                        ) {
                            Text(
                                text = stringResource(R.string.auth_continue_google),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPhoneOtpClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = PaperSunken.copy(alpha = 0.55f),
                                    contentColor = Ink
                                ),
                                border = BorderStroke(1.dp, InkHairline)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = stringResource(R.string.cd_phone_otp),
                                    tint = Ink,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.auth_phone_sms),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = { if (!isLoading) viewModel.signInAnonymously() },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = PaperSunken.copy(alpha = 0.55f),
                                    contentColor = Ink
                                ),
                                border = BorderStroke(1.dp, InkHairline)
                            ) {
                                Text(
                                    text = stringResource(R.string.auth_guest_rider),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.auth_reset_password_title),
                            color = Ink,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.auth_reset_password_desc),
                            color = InkSecondary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                        )

                        OutlinedTextField(
                            value = forgotEmail,
                            onValueChange = { forgotEmail = it.trim() },
                            label = { Text(stringResource(R.string.auth_registered_email), color = InkSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
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
                            colors = fieldColors
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (forgotEmail.isNotBlank()) viewModel.sendPasswordReset(forgotEmail)
                            },
                            enabled = !isLoading && forgotEmail.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Pop,
                                contentColor = PaperOnPop
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = PaperOnPop,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    stringResource(R.string.auth_send_reset_link),
                                    color = PaperOnPop,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        TextButton(
                            onClick = { showForgotPassword = false },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(
                                stringResource(R.string.auth_return_to_sign_in),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (!showForgotPassword) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.auth_new_to_app),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = onRegisterClick,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            stringResource(R.string.action_create_account),
                            color = Pop,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.auth_terms_login),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}

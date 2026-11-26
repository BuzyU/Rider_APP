package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.ui.components.InlineError
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.RvScreen
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.JoinRoomViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.ridervoice.ui.components.rvBringIntoViewOnFocus
import com.ridervoice.ui.components.RvTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinRoomScreen(
    initialToken: String? = null,
    initialRoomName: String = "",
    onBackClick: () -> Unit = {},
    onJoin: (roomName: String, userName: String) -> Unit,
    viewModel: JoinRoomViewModel = hiltViewModel()
) {
    val focusManager = LocalFocusManager.current
    val currentUser = remember { FirebaseAuth.getInstance().currentUser }
    var roomCode by rememberSaveable(initialRoomName) { mutableStateOf(initialRoomName) }
    var userName by rememberSaveable {
        mutableStateOf(currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "Rider")
    }

    val isTokenConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val tokenError by viewModel.error.collectAsStateWithLifecycle()

    var activeToken by rememberSaveable(initialToken) { mutableStateOf(initialToken?.takeIf { it.isNotBlank() }) }
    LaunchedEffect(activeToken) {
        activeToken?.let { tok ->
            viewModel.joinViaToken(tok) { roomName ->
                onJoin(roomName, userName)
            }
        }
    }

    val canJoin = roomCode.isNotBlank() && userName.isNotBlank() && !isTokenConnecting

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkBorderStrong,
        containerColor = PaperSunken,
        textColor = Ink,
        cursorColor = Pop
    )

    RvScreen(
        title = stringResource(R.string.action_join_ride),
        onBack = onBackClick,
        bottomBar = if (activeToken == null) {
            {
                PrimaryButton(
                    text = if (isTokenConnecting) stringResource(R.string.join_connecting_short) else stringResource(R.string.join_button_label),
                    onClick = {
                        if (canJoin) {
                            viewModel.smartJoin(roomCode.trim(), userName.trim()) { resolvedRoom ->
                                onJoin(resolvedRoom, userName.trim())
                            }
                        }
                    },
                    enabled = canJoin,
                    loading = isTokenConnecting
                )
            }
        } else null
    ) {
        if (activeToken != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isTokenConnecting) {
                        CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.join_connecting),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.join_verifying_link),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                    } else if (tokenError != null) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Brick,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.join_failed_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Brick
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tokenError ?: stringResource(R.string.join_link_expired_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { activeToken = null },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text(stringResource(R.string.join_action_enter_code), color = Ink, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    activeToken?.let { tok ->
                                        viewModel.joinViaToken(tok) { onJoin(it, userName) }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Pop),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text(stringResource(R.string.action_retry), color = PaperOnPop, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(RvSpacing.sm))

            Text(
                text = stringResource(R.string.join_intro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            RvTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text(stringResource(R.string.join_callsign_label)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = InkSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("join_username_field"),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(14.dp))

            RvTextField(
                value = roomCode,
                onValueChange = { roomCode = it },
                label = { Text(stringResource(R.string.join_code_or_link_label)) },
                placeholder = { Text(stringResource(R.string.join_code_placeholder), color = InkSecondary) },
                leadingIcon = { Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = Pop) },
                supportingText = {
                    if (roomCode.isBlank()) {
                        Text(
                            stringResource(R.string.join_code_helper),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        if (canJoin) {
                            viewModel.smartJoin(roomCode.trim(), userName.trim()) { resolvedRoom ->
                                onJoin(resolvedRoom, userName.trim())
                            }
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("join_room_code_field"),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors
            )

            if (tokenError != null && activeToken == null) {
                Spacer(modifier = Modifier.height(14.dp))
                InlineError(message = tokenError ?: stringResource(R.string.join_failed_title))
            }

            Spacer(modifier = Modifier.height(RvSpacing.xl))
        }
    }
}

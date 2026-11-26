package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.ui.components.*
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HostSetupViewModel
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.ridervoice.ui.components.RvTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    viewModel: HostSetupViewModel = hiltViewModel(),
    onConvoyCreated: (String) -> Unit,
    onBackClick: () -> Unit,
    onImportRouteClick: () -> Unit = {}
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val createState by viewModel.createState.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    var convoyName by rememberSaveable { mutableStateOf("") }
    var meetupPoint by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var diagnosticsEnabled by rememberSaveable { mutableStateOf(false) }
    var moreOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showLearnMoreSheet by rememberSaveable { mutableStateOf(false) }

    val clientRequestId = rememberSaveable { viewModel.getOrCreateClientRequestId() }

    val isDirty = convoyName.isNotBlank() || origin.isNotBlank() || destination.isNotBlank() || meetupPoint.isNotBlank()
    val isNameValid = convoyName.trim().length in 3..40
    val isSubmitting = createState is HostSetupViewModel.CreateRideState.Submitting || isLoading
    val canCreate = isNameValid && !isSubmitting

    BackHandler {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            viewModel.clearPendingAttempt()
            onBackClick()
        }
    }

    LaunchedEffect(createState) {
        if (createState is HostSetupViewModel.CreateRideState.Succeeded) {
            val name = (createState as HostSetupViewModel.CreateRideState.Succeeded).convoyName
            viewModel.onNavigationHandled()
            onConvoyCreated(name)
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = stringResource(R.string.host_discard_title),
            body = stringResource(R.string.host_discard_body),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.host_keep_editing),
            onConfirm = {
                showDiscardDialog = false
                viewModel.clearPendingAttempt()
                onBackClick()
            },
            onDismiss = { showDiscardDialog = false },
            destructive = true
        )
    }

    if (showLearnMoreSheet) {
        AlertDialog(
            onDismissRequest = { showLearnMoreSheet = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = PaperSunken,
            title = {
                Text(
                    text = stringResource(R.string.create_ride_diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.host_diag_learn_more_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showLearnMoreSheet = false },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pop)
                ) {
                    Text(stringResource(R.string.action_got_it), color = PaperOnPop, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkBorderStrong,
        containerColor = PaperSunken,
        textColor = Ink,
        cursorColor = Pop
    )

    RvScreen(
        title = stringResource(R.string.create_ride_title),
        onBack = {
            if (isDirty) {
                showDiscardDialog = true
            } else {
                viewModel.clearPendingAttempt()
                onBackClick()
            }
        },
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.action_create_ride),
                onClick = {
                    viewModel.createConvoy(
                        convoyName = convoyName.trim(),
                        origin = origin.trim().ifBlank { null },
                        destination = destination.trim().ifBlank { null },
                        meetupPoint = meetupPoint.trim().ifBlank { null },
                        diagnosticsEnabled = diagnosticsEnabled,
                        clientRequestId = clientRequestId
                    )
                },
                enabled = canCreate,
                loading = isSubmitting
            )
        }
    ) {
        Spacer(modifier = Modifier.height(RvSpacing.sm))

        Text(
            text = stringResource(R.string.host_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 1. Ride Name (required, 3-40 chars)
        RvTextField(
            value = convoyName,
            onValueChange = { convoyName = it.take(40) },
            label = { Text(stringResource(R.string.create_ride_name_label)) },
            placeholder = { Text(stringResource(R.string.create_ride_name_placeholder), color = InkSecondary) },
            supportingText = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.create_ride_name_helper), color = InkSecondary)
                    Text("${convoyName.length}/40", color = InkSecondary)
                }
            },
            isError = convoyName.isNotBlank() && !isNameValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_convoy_name_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Meet-up point (optional)
        RvTextField(
            value = meetupPoint,
            onValueChange = { meetupPoint = it },
            label = { Text(stringResource(R.string.create_ride_meetup_label)) },
            placeholder = { Text(stringResource(R.string.host_meetup_placeholder), color = InkSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_meetup_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Destination (optional)
        RvTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text(stringResource(R.string.create_ride_destination_label)) },
            placeholder = { Text(stringResource(R.string.host_dest_placeholder), color = InkSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_destination_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Start (optional)
        RvTextField(
            value = origin,
            onValueChange = { origin = it },
            label = { Text(stringResource(R.string.host_start_label)) },
            placeholder = { Text(stringResource(R.string.host_start_placeholder), color = InkSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (canCreate) {
                        viewModel.createConvoy(
                            convoyName = convoyName.trim(),
                            origin = origin.trim().ifBlank { null },
                            destination = destination.trim().ifBlank { null },
                            meetupPoint = meetupPoint.trim().ifBlank { null },
                            diagnosticsEnabled = diagnosticsEnabled,
                            clientRequestId = clientRequestId
                        )
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_origin_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Collapsible "More options" Bento Card
        Surface(
            color = PaperRaised,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, InkHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { moreOptionsExpanded = !moreOptionsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = Pop,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.create_ride_more_options),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                    }
                    Icon(
                        imageVector = if (moreOptionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = InkSecondary
                    )
                }

                AnimatedVisibility(visible = moreOptionsExpanded) {
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        Divider(color = InkHairline)
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = diagnosticsEnabled,
                                    role = Role.Switch,
                                    onValueChange = { diagnosticsEnabled = it }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = diagnosticsEnabled,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PaperOnPop,
                                    checkedTrackColor = Pop,
                                    uncheckedThumbColor = InkSecondary,
                                    uncheckedTrackColor = PaperSunken
                                )
                            )
                        }
                        TextButton(
                            onClick = { showLearnMoreSheet = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.action_learn_more), color = Pop, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SecondaryButton(
                            text = stringResource(R.string.host_import_route),
                            onClick = onImportRouteClick,
                            leadingIcon = Icons.Default.Map
                        )
                    }
                }
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(14.dp))
            InlineError(
                message = error ?: stringResource(R.string.host_create_failed),
                onRetry = {
                    viewModel.createConvoy(
                        convoyName = convoyName.trim(),
                        origin = origin.trim().ifBlank { null },
                        destination = destination.trim().ifBlank { null },
                        meetupPoint = meetupPoint.trim().ifBlank { null },
                        diagnosticsEnabled = diagnosticsEnabled,
                        clientRequestId = clientRequestId
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(RvSpacing.xl))
    }
}

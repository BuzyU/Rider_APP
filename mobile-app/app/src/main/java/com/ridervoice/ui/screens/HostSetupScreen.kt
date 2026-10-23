package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.ui.components.*
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HostSetupViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    viewModel: HostSetupViewModel = hiltViewModel(),
    onConvoyCreated: (String) -> Unit,
    onBackClick: () -> Unit,
    onImportRouteClick: () -> Unit = {}
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val createdConvoyName by viewModel.createdConvoyName.collectAsState()

    var convoyName by remember { mutableStateOf("") }
    var meetupPoint by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableIntStateOf(60) }
    var diagnosticsEnabled by remember { mutableStateOf(false) }
    var moreOptionsExpanded by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showLearnMoreSheet by remember { mutableStateOf(false) }

    val clientRequestId = remember { UUID.randomUUID().toString() }

    val isDirty = convoyName.isNotBlank() || origin.isNotBlank() || destination.isNotBlank() || meetupPoint.isNotBlank()
    val isNameValid = convoyName.trim().length in 3..40
    val canCreate = isNameValid && !isLoading

    BackHandler {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onBackClick()
        }
    }

    LaunchedEffect(createdConvoyName) {
        createdConvoyName?.let { onConvoyCreated(it) }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "Discard ride setup?",
            body = "You have unsaved ride details. Discarding will clear all fields.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onConfirm = {
                showDiscardDialog = false
                onBackClick()
            },
            onDismiss = { showDiscardDialog = false },
            destructive = true
        )
    }

    if (showLearnMoreSheet) {
        AlertDialog(
            onDismissRequest = { showLearnMoreSheet = false },
            containerColor = DarkSlate,
            title = {
                Text(
                    text = "Network test (beta)",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Enabling the network test helps the RiderVoice team improve audio reliability on motorcycles.\n\nOnly connection stats (bitrate, packet loss, jitter) and audio routing status are collected. We never record your audio content, and location tracking is separate and strictly optional.\n\nAt the end of your ride, an aggregated summary is sent to the engineering team.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showLearnMoreSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Text("Got it", color = Color.White)
                }
            }
        )
    }

    RvScreen(
        title = stringResource(R.string.create_ride_title),
        onBack = {
            if (isDirty) showDiscardDialog = true else onBackClick()
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
                        estimatedDurationMin = durationMinutes,
                        diagnosticsEnabled = diagnosticsEnabled,
                        clientRequestId = clientRequestId
                    )
                },
                enabled = canCreate,
                loading = isLoading
            )
        }
    ) {
        Spacer(modifier = Modifier.height(RvSpacing.sm))

        // 1. Ride Name (required, 3-40 chars)
        OutlinedTextField(
            value = convoyName,
            onValueChange = { convoyName = it.take(40) },
            label = { Text(stringResource(R.string.create_ride_name_label)) },
            placeholder = { Text(stringResource(R.string.create_ride_name_placeholder)) },
            supportingText = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.create_ride_name_helper), color = TextSecondary)
                    Text("${convoyName.length}/40", color = TextSecondary)
                }
            },
            isError = convoyName.isNotBlank() && !isNameValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RvRadius.sm),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = BorderColor,
                containerColor = DarkSlate,
                textColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(RvSpacing.md))

        // 2. Meet-up point (optional)
        OutlinedTextField(
            value = meetupPoint,
            onValueChange = { meetupPoint = it },
            label = { Text(stringResource(R.string.create_ride_meetup_label)) },
            placeholder = { Text("e.g. Shell Petrol Pump, Hinjewadi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RvRadius.sm),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = BorderColor,
                containerColor = DarkSlate,
                textColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(RvSpacing.md))

        // 3. Destination (optional)
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text(stringResource(R.string.create_ride_destination_label)) },
            placeholder = { Text("e.g. Tiger Point, Lonavala") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RvRadius.sm),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = BorderColor,
                containerColor = DarkSlate,
                textColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(RvSpacing.md))

        // 4. Start (optional)
        OutlinedTextField(
            value = origin,
            onValueChange = { origin = it },
            label = { Text("Start point (optional)") },
            placeholder = { Text("e.g. University Circle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RvRadius.sm),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = NeonOrange,
                unfocusedBorderColor = BorderColor,
                containerColor = DarkSlate,
                textColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(RvSpacing.lg))

        // 5. Estimated Time Stepper (15 min steps)
        Text(
            text = stringResource(R.string.create_ride_duration_label),
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(RvSpacing.xs))

        Surface(
            color = DarkSlate,
            shape = RoundedCornerShape(RvRadius.sm),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { if (durationMinutes > 15) durationMinutes -= 15 },
                    enabled = durationMinutes > 15,
                    modifier = Modifier.size(RvTouchTarget.min)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease duration",
                        tint = if (durationMinutes > 15) TextPrimary else TextSecondary
                    )
                }

                val hours = durationMinutes / 60
                val mins = durationMinutes % 60
                val durationText = buildString {
                    if (hours > 0) append("$hours hr${if (hours > 1) "s" else ""} ")
                    if (mins > 0 || hours == 0) append("$mins min")
                }.trim()

                Text(
                    text = durationText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonOrange
                )

                IconButton(
                    onClick = { if (durationMinutes < 720) durationMinutes += 15 },
                    enabled = durationMinutes < 720,
                    modifier = Modifier.size(RvTouchTarget.min)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase duration",
                        tint = if (durationMinutes < 720) TextPrimary else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(RvSpacing.lg))

        // 6. Collapsed "More options"
        Surface(
            color = DarkSlate,
            shape = RoundedCornerShape(RvRadius.md),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(RvSpacing.md)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { moreOptionsExpanded = !moreOptionsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = NeonOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(RvSpacing.sm))
                        Text(
                            text = stringResource(R.string.create_ride_more_options),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Icon(
                        imageVector = if (moreOptionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }

                AnimatedVisibility(visible = moreOptionsExpanded) {
                    Column(modifier = Modifier.padding(top = RvSpacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                TextButton(
                                    onClick = { showLearnMoreSheet = true },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Learn more", color = NeonOrange, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(modifier = Modifier.width(RvSpacing.sm))
                            Switch(
                                checked = diagnosticsEnabled,
                                onCheckedChange = { diagnosticsEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NeonOrange,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = Gunmetal
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(RvSpacing.md))

                        SecondaryButton(
                            text = "Import planned route",
                            onClick = onImportRouteClick,
                            leadingIcon = Icons.Default.Map
                        )
                    }
                }
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(RvSpacing.md))
            InlineError(
                message = error ?: "Failed to create ride",
                onRetry = {
                    viewModel.createConvoy(
                        convoyName = convoyName.trim(),
                        origin = origin.trim().ifBlank { null },
                        destination = destination.trim().ifBlank { null },
                        meetupPoint = meetupPoint.trim().ifBlank { null },
                        estimatedDurationMin = durationMinutes,
                        diagnosticsEnabled = diagnosticsEnabled,
                        clientRequestId = clientRequestId
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(RvSpacing.xl))
    }
}

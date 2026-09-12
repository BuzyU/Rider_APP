package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HostSetupViewModel

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
    val isDark = ThemeState.isDarkTheme

    var convoyName      by remember { mutableStateOf("") }
    var origin          by remember { mutableStateOf("") }
    var destination     by remember { mutableStateOf("") }
    var meetupPoint     by remember { mutableStateOf("") }
    var durationHours   by remember { mutableStateOf("") }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val isDirty = convoyName.isNotBlank() || origin.isNotBlank() || destination.isNotBlank()
    val isDurationError = durationHours.isNotBlank() && durationHours.toFloatOrNull() == null
    val canCreate = convoyName.isNotBlank() && !isDurationError

    BackHandler {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onBackClick()
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Text(
                    text = "DISCARD CONVOY SETUP?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "You have unsaved convoy details. Discarding will clear all trip fields.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("DISCARD", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDiscardDialog = false }) {
                    Text("KEEP EDITING", color = TextPrimary)
                }
            },
            containerColor = DarkSlate
        )
    }

    LaunchedEffect(createdConvoyName) {
        createdConvoyName?.let { onConvoyCreated(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (isDirty) showDiscardDialog = true else onBackClick()
            }) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "CONVOY DISPATCH BENCH",
                    color = NeonOrange,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "NAME YOUR CONVOY",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            SectionLabel("CONVOY CALLSIGN / NAME *")
            TacticalTextField(
                value = convoyName,
                onValueChange = { convoyName = it.take(40) },
                placeholder = "e.g. Pune Sunday Ghats Run",
                supportingText = "${convoyName.length}/40"
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("TRIP DETAILS (SHOWN TO INVITees)")

                TextButton(
                    onClick = {
                        origin = "Pune University Circle"
                        destination = "Tiger Point, Lonavala"
                        meetupPoint = "Chandani Chowk Shell Fuel Station"
                        durationHours = "2.5"
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "FILL FROM ROUTE PLANNER",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }
            }

            TacticalTextField(
                value = origin,
                onValueChange = { origin = it },
                placeholder = "Starting point / departure landmark"
            )

            Spacer(modifier = Modifier.height(10.dp))

            TacticalTextField(
                value = destination,
                onValueChange = { destination = it },
                placeholder = "Final destination point"
            )

            Spacer(modifier = Modifier.height(10.dp))

            TacticalTextField(
                value = meetupPoint,
                onValueChange = { meetupPoint = it },
                placeholder = "Convoy staging / meetup point (optional)"
            )

            Spacer(modifier = Modifier.height(10.dp))

            TacticalTextField(
                value = durationHours,
                onValueChange = { durationHours = it },
                placeholder = "Estimated duration in hours (e.g. 2.5)",
                keyboardType = KeyboardType.Decimal,
                isError = isDurationError,
                supportingText = if (isDurationError) "Enter numeric hours (e.g. 2.5)" else null
            )

            Spacer(modifier = Modifier.height(24.dp))

            error?.let { err ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = HazardContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unable to Create Convoy",
                                color = AlertRed,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = err,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                viewModel.createConvoy(
                                    convoyName = convoyName.trim(),
                                    origin = origin.trim().ifBlank { null },
                                    destination = destination.trim().ifBlank { null },
                                    meetupPoint = meetupPoint.trim().ifBlank { null },
                                    estimatedDurationMin = durationHours.trim().toFloatOrNull()?.let { (it * 60).toInt() }
                                )
                            },
                            enabled = canCreate && !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Retry", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.createConvoy(
                        convoyName = convoyName.trim(),
                        origin = origin.trim().ifBlank { null },
                        destination = destination.trim().ifBlank { null },
                        meetupPoint = meetupPoint.trim().ifBlank { null },
                        estimatedDurationMin = durationHours.trim().toFloatOrNull()?.let { (it * 60).toInt() }
                    )
                },
                enabled = canCreate && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonOrange,
                    disabledContainerColor = Gunmetal
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = if (isDark) Color.Black else Color.White,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "CREATE CONVOY & INVITE SQUAD →",
                        color = if (canCreate) (if (isDark) Color.Black else Color.White) else TextSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TacticalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    supportingText: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        isError = isError,
        placeholder = { Text(placeholder, color = TextSecondary, fontSize = 13.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText?.let { { Text(it, color = if (isError) AlertRed else TextSecondary, fontSize = 11.sp) } },
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = NeonOrange,
            unfocusedBorderColor = BorderColor,
            containerColor = DarkSlate,
            textColor = TextPrimary
        )
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

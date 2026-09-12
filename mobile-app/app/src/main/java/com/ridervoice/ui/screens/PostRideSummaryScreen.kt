package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRideSummaryScreen(
    durationMinutes: Int,
    distanceKm: Float,
    topSpeed: Float,
    onSaveToLogbook: (privacy: String) -> Unit,
    onDiscard: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme
    var selectedPrivacy by remember { mutableStateOf("SQUAD") }
    var rideTitle by remember { mutableStateOf("") }
    var rideNotes by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text(
                    text = "DISCARD RIDE RECORDING?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to discard this ride? All GPS waypoints, telemetry curves, and flight data will be permanently erased.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmDialog = false
                        onDiscard()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("DISCARD PERMANENTLY", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDiscardConfirmDialog = false }) {
                    Text("KEEP RECORDING", color = TextPrimary)
                }
            },
            containerColor = DarkSlate
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = DarkSlate,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderColor),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "TRANSCEIVER FLIGHT LOG",
                    color = NeonOrange,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "RIDE COMPLETE",
                    color = TextPrimary,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 28.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    color = Gunmetal,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox("TIME", "${durationMinutes}M")
                        StatBox("DISTANCE", "${String.format("%.1f", distanceKm)} KM")

                        StatBox("TOP SPEED", "${String.format("%.0f", topSpeed)} KM/H")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = rideTitle,
                    onValueChange = { rideTitle = it },
                    placeholder = { Text("Ride Title (e.g. Tiger Point Monsoon Run)", color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = DarkSlate,
                        textColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = rideNotes,
                    onValueChange = { rideNotes = it },
                    placeholder = { Text("Trip Notes / Route condition (optional)", color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = DarkSlate,
                        textColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "TELEMETRY PRIVACY LEVEL",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrivacyChip(
                        label = "SQUAD VISIBLE",
                        isSelected = selectedPrivacy == "SQUAD",
                        modifier = Modifier.weight(1f)
                    ) { selectedPrivacy = "SQUAD" }

                    PrivacyChip(
                        label = "PRIVATE LOGBOOK",
                        isSelected = selectedPrivacy == "PRIVATE",
                        modifier = Modifier.weight(1f)
                    ) { selectedPrivacy = "PRIVATE" }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        isSaving = true
                        onSaveToLogbook(selectedPrivacy)
                    },
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SAVING TELEMETRY...",
                            color = if (isDark) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        Text(
                            text = "SAVE TO LOGBOOK",
                            color = if (isDark) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = { showDiscardConfirmDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        text = "DISCARD RECORDING",
                        color = AlertRed,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
        )
    }
}

@Composable
fun PrivacyChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme

    Surface(
        color = if (isSelected) (if (isDark) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt) else DarkSlate,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) NeonOrange else BorderColor
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (isSelected) NeonOrange else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
    }
}

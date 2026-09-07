package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

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
    onBackClick: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val createdConvoyName by viewModel.createdConvoyName.collectAsState()

    // Navigate when convoy created successfully
    LaunchedEffect(createdConvoyName) {
        createdConvoyName?.let { onConvoyCreated(it) }
    }

    var convoyName      by remember { mutableStateOf("") }
    var origin          by remember { mutableStateOf("") }
    var destination     by remember { mutableStateOf("") }
    var meetupPoint     by remember { mutableStateOf("") }
    var durationHours   by remember { mutableStateOf("") }

    val canCreate = convoyName.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBackIosNew, "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text("HOST A RIDE", color = TextSecondary, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text("Name your convoy", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Convoy name (required) ───────────────────────────────────
            SectionLabel("CONVOY NAME *")
            TacticalTextField(
                value = convoyName,
                onValueChange = { convoyName = it.take(40) },
                placeholder = "e.g. Pune Night Riders",
                supportingText = "${convoyName.length}/40"
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel("TRIP DETAILS  (optional — shown to invitees)")

            TacticalTextField(
                value = origin,
                onValueChange = { origin = it },
                placeholder = "Starting point / meetup location"
            )

            Spacer(modifier = Modifier.height(12.dp))

            TacticalTextField(
                value = destination,
                onValueChange = { destination = it },
                placeholder = "Destination"
            )

            Spacer(modifier = Modifier.height(12.dp))

            TacticalTextField(
                value = meetupPoint,
                onValueChange = { meetupPoint = it },
                placeholder = "Meetup point (optional)"
            )

            Spacer(modifier = Modifier.height(12.dp))

            TacticalTextField(
                value = durationHours,
                onValueChange = { durationHours = it },
                placeholder = "Estimated duration (hours)",
                keyboardType = KeyboardType.Decimal
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Error
            error?.let { err ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = AlertRed.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unable to Create Convoy",
                                color = AlertRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = err,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
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
                            Text("Retry", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Create button
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
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonOrange,
                    disabledContainerColor = Gunmetal
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("CREATE CONVOY & INVITE RIDERS →", color = TextPrimary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
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
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = TextSecondary, fontSize = 14.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText?.let { { Text(it, color = TextSecondary, fontSize = 11.sp) } },
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

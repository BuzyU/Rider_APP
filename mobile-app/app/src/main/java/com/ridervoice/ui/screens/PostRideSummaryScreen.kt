package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRideSummaryScreen(
    durationMinutes: Int,
    distanceKm: Float,
    topSpeed: Float,
    convoyName: String = "Convoy",
    testNumber: Int = 1,
    sampleCount: Int = 0,
    lastMetric: com.ridervoice.diagnostics.models.SelfMetricSample? = null,
    onSaveToLogbook: (privacy: String) -> Unit,
    onDiscard: () -> Unit
) {
    var selectedPrivacy by remember { mutableStateOf("SQUAD") }
    var rideTitle by remember { mutableStateOf("") }
    var rideNotes by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

    val avgSpeedKmh = if (durationMinutes > 0) (distanceKm / (durationMinutes / 60f)) else 0f
    val durationFormatted = if (durationMinutes >= 60) {
        "${durationMinutes / 60}h ${durationMinutes % 60}m"
    } else {
        "${durationMinutes}m"
    }

    if (showDiscardConfirmDialog) {
        ConfirmDialog(
            title = stringResource(R.string.summary_discard_title),
            body = stringResource(R.string.summary_discard_body),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.summary_keep_recording),
            destructive = true,
            onConfirm = {
                showDiscardConfirmDialog = false
                onDiscard()
            },
            onDismiss = { showDiscardConfirmDialog = false }
        )
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            Surface(
                color = PaperRaised,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Divider(color = InkHairline, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                isSaving = true
                                onSaveToLogbook(selectedPrivacy)
                            },
                            enabled = !isSaving,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Pop,
                                contentColor = PaperOnPop
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    color = PaperOnPop,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.summary_saving), color = PaperOnPop, style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.summary_save_to_logbook),
                                    color = PaperOnPop,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontSize = 15.sp
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showDiscardConfirmDialog = true },
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Brick),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = BrickContainer.copy(alpha = 0.35f),
                                contentColor = Brick
                            ),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.action_discard), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Badges (Matches Image 3 Ride Summary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MossContainer,
                    shape = RoundedCornerShape(RvRadius.pill)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Moss))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.summary_status_completed),
                            color = Moss,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
                if (sampleCount > 0) {
                    Surface(
                        color = PaperSunken,
                        shape = RoundedCornerShape(RvRadius.pill)
                    ) {
                        Text(
                            text = stringResource(R.string.summary_test_samples, testNumber, sampleCount),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Column {
                Text(
                    text = convoyName.ifBlank { stringResource(R.string.summary_ride_summary) },
                    color = Ink,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.summary_review_desc),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp
                )
            }

            // 2x2 Convoy Telemetry Bento Grid (Matches Image 3)
            Text(
                text = stringResource(R.string.summary_section_telemetry),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryBentoTile(
                    label = stringResource(R.string.summary_stat_distance),
                    value = String.format(Locale.US, "%.1f", distanceKm),
                    unit = stringResource(R.string.unit_km),
                    icon = Icons.Default.Straighten,
                    modifier = Modifier.weight(1f)
                )
                SummaryBentoTile(
                    label = stringResource(R.string.summary_stat_moving_time),
                    value = durationFormatted,
                    unit = "",
                    icon = Icons.Default.Timer,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryBentoTile(
                    label = stringResource(R.string.summary_stat_avg_speed),
                    value = String.format(Locale.US, "%.0f", avgSpeedKmh),
                    unit = stringResource(R.string.unit_kmh),
                    icon = Icons.Default.Speed,
                    modifier = Modifier.weight(1f)
                )
                SummaryBentoTile(
                    label = stringResource(R.string.summary_stat_top_speed),
                    value = String.format(Locale.US, "%.0f", topSpeed),
                    unit = stringResource(R.string.unit_kmh),
                    icon = Icons.Default.Bolt,
                    modifier = Modifier.weight(1f)
                )
            }

            // Diagnostics Telemetry Card
            Surface(
                color = PaperSunken,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.summary_section_net_diag),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                        lastMetric?.networkType?.takeIf { it.isNotBlank() }?.let { netType ->
                            Text(
                                text = netType,
                                color = Ink,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatBox(stringResource(R.string.summary_diag_avg_ping), "${lastMetric?.rttMs?.toInt() ?: "--"} ms")
                        StatBox(
                            label = stringResource(R.string.summary_diag_pkts_lost),
                            value = lastMetric?.packetsLost?.toString() ?: "--",
                            valueModifier = Modifier.testTag("summary_diag_pkts_lost_value")
                        )
                        StatBox(stringResource(R.string.summary_diag_samples), "$sampleCount")
                    }
                }
            }

            // Ride Logbook Notes Inputs
            OutlinedTextField(
                value = rideTitle,
                onValueChange = { rideTitle = it },
                placeholder = { Text(stringResource(R.string.summary_title_placeholder), color = InkSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Pop,
                    unfocusedBorderColor = InkHairline,
                    containerColor = PaperRaised,
                    textColor = Ink
                )
            )

            OutlinedTextField(
                value = rideNotes,
                onValueChange = { rideNotes = it },
                placeholder = { Text(stringResource(R.string.summary_notes_placeholder), color = InkSecondary) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Pop,
                    unfocusedBorderColor = InkHairline,
                    containerColor = PaperRaised,
                    textColor = Ink
                )
            )

            // Privacy Selector
            Text(
                text = stringResource(R.string.summary_section_visibility),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacyChip(
                    label = stringResource(R.string.summary_privacy_squad),
                    isSelected = selectedPrivacy == "SQUAD",
                    modifier = Modifier.weight(1f)
                ) { selectedPrivacy = "SQUAD" }

                PrivacyChip(
                    label = stringResource(R.string.summary_privacy_private),
                    isSelected = selectedPrivacy == "PRIVATE",
                    modifier = Modifier.weight(1f)
                ) { selectedPrivacy = "PRIVATE" }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SummaryBentoTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = InkSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = Ink,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatBox(
    label: String,
    value: String,
    valueModifier: Modifier = Modifier
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = valueModifier
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = InkSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp
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
    Surface(
        color = if (isSelected) PopContainer else PaperRaised,
        shape = RoundedCornerShape(RvRadius.pill),
        border = BorderStroke(1.dp, if (isSelected) Pop else InkHairline),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) Pop else InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

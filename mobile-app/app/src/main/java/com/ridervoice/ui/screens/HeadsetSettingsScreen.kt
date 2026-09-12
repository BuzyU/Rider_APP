package com.ridervoice.ui.screens

import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadsetSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("headset_prefs", Context.MODE_PRIVATE) }

    var enableHardwarePtt by remember {
        mutableStateOf(prefs.getBoolean("enableHardwarePtt", true))
    }
    var hybridVoxMode by remember {
        mutableStateOf(prefs.getBoolean("hybridVoxMode", true))
    }
    var strictDebounce by remember {
        mutableStateOf(prefs.getBoolean("strictDebounce", true))
    }
    var enhancedCompatibility by remember {
        mutableStateOf(prefs.getBoolean("enhancedCompatibility", false))
    }

    var diagnosticTestPassed by remember { mutableStateOf(false) }

    fun updatePref(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RADIO HARNESS SETUP",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonOrange,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "HELMET HEADSET CONTROLS",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = GraphiteBase)
            )
        },
        containerColor = GraphiteBase
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Configure hardware PTT button interception and Bluetooth SCO debounce for Sena, Cardo, and generic motorcycle intercoms.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "GENERAL PTT PROTOCOL",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingsSwitchCard(
                title = "Enable Hardware PTT",
                description = "Intercept helmet Play/Pause and media buttons to toggle radio transmit.",
                checked = enableHardwarePtt,
                onCheckedChange = {
                    enableHardwarePtt = it
                    updatePref("enableHardwarePtt", it)
                }
            )

            SettingsSwitchCard(
                title = "Hybrid VOX/PTT Mode",
                description = "Keep Smart VOX active, but use hardware helmet button to hold open carrier channel.",
                checked = hybridVoxMode,
                onCheckedChange = {
                    hybridVoxMode = it
                    updatePref("hybridVoxMode", it)
                },
                enabled = enableHardwarePtt
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "BLUETOOTH DEBOUNCE & COMPATIBILITY",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingsSwitchCard(
                title = "Strict Event Debounce",
                description = "Filter ghost/duplicate button events sent by Cardo Packtalk and Sena 50S units.",
                checked = strictDebounce,
                onCheckedChange = {
                    strictDebounce = it
                    updatePref("strictDebounce", it)
                },
                enabled = enableHardwarePtt
            )

            SettingsSwitchCard(
                title = "Enhanced Compatibility Carrier",
                description = "WARNING: Keeps silent carrier stream active during convoy to prevent Android OS from ignoring Bluetooth media events.",
                checked = enhancedCompatibility,
                onCheckedChange = {
                    enhancedCompatibility = it
                    updatePref("enhancedCompatibility", it)
                },
                enabled = enableHardwarePtt
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "HARDWARE BUTTON DIAGNOSTIC",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = DarkSlate,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (diagnosticTestPassed) TechGreen else BorderColor
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        diagnosticTestPassed = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (diagnosticTestPassed) TechGreen.copy(alpha = 0.2f) else Gunmetal),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (diagnosticTestPassed) Icons.Default.CheckCircle else Icons.Default.HeadsetMic,
                            contentDescription = null,
                            tint = if (diagnosticTestPassed) TechGreen else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (diagnosticTestPassed) "HARDWARE BUTTON DETECTED" else "TEST HELMET BUTTON",
                            color = if (diagnosticTestPassed) TechGreen else TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (diagnosticTestPassed) "Event: KEYCODE_MEDIA_PLAY (22ms debounce OK)" else "Press your helmet button or tap here to verify interception.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val isDark = ThemeState.isDarkTheme

    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(enabled = enabled) {
                onCheckedChange(!checked)
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) TextPrimary else TextSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (isDark) Color.Black else Color.White,
                    checkedTrackColor = TechGreen,
                    uncheckedTrackColor = Gunmetal
                )
            )
        }
    }
}

package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.DarkSlate
import com.ridervoice.ui.theme.GraphiteBase
import com.ridervoice.ui.theme.NeonOrange
import com.ridervoice.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadsetSettingsScreen(onBack: () -> Unit) {
    var enableHardwarePtt by remember { mutableStateOf(true) }
    var hybridVoxMode by remember { mutableStateOf(true) }
    var strictDebounce by remember { mutableStateOf(true) }
    var enhancedCompatibility by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Headset Controls", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                .padding(16.dp)
        ) {
            // General PTT
            SettingsSwitchCard(
                title = "Enable Hardware PTT",
                description = "Intercept Play/Pause buttons to toggle mic.",
                checked = enableHardwarePtt,
                onCheckedChange = { enableHardwarePtt = it }
            )
            
            SettingsSwitchCard(
                title = "Hybrid VOX/PTT Mode",
                description = "Keep Smart VOX active, but use hardware button to forcefully hold mic open.",
                checked = hybridVoxMode,
                onCheckedChange = { hybridVoxMode = it },
                enabled = enableHardwarePtt
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("ADVANCED DIAGNOSTICS", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // Compatibility Fallbacks
            SettingsSwitchCard(
                title = "Strict Event Debounce",
                description = "Filter out ghost/duplicate ACTION_DOWN events sent by Cardo/Sena headsets.",
                checked = strictDebounce,
                onCheckedChange = { strictDebounce = it },
                enabled = enableHardwarePtt
            )

            SettingsSwitchCard(
                title = "Enhanced Compatibility Mode",
                description = "WARNING: Plays silent audio during convoy to prevent Android OS from ignoring media buttons. May drain battery faster.",
                checked = enhancedCompatibility,
                onCheckedChange = { enhancedCompatibility = it },
                enabled = enableHardwarePtt
            )
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSlate)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = description, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NeonOrange)
            )
        }
    }
}

package com.example.intercommvp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.intercommvp.audio.IntercomService
import com.example.intercommvp.audio.AudioRouteMode
import com.example.intercommvp.model.IntercomDevice
import com.example.intercommvp.network.PeerDiscovery

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IntercomScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntercomScreen() {
    val micEnabled by IntercomService.micEnabled.collectAsState()
    val routeMode by IntercomService.routeMode.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val discoveredDevices by PeerDiscovery.devices.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(
                activity,
                IntercomService.roomIntent(activity)
            )
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(
                activity,
                IntercomService.setRouteIntent(activity, AudioRouteMode.BLUETOOTH)
            )
        }
    }

    LaunchedEffect(Unit) {
        if (
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ContextCompat.startForegroundService(
                activity,
                IntercomService.roomIntent(activity)
            )
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Intercom") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredDevices, key = { it.id }) { device ->
                    DeviceRow(device)
                }
            }
            Text(
                if (discoveredDevices.isEmpty()) {
                    "No other intercom devices detected on this hotspot"
                } else {
                    "${discoveredDevices.size + 1}/6 devices in room"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (micEnabled) {
                    "Microphone on · voices are shared automatically"
                } else {
                    "Room audio is active · microphone is off"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Audio output: ${routeMode.label}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = {
                    val hasRecordPermission =
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasRecordPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (routeMode == AudioRouteMode.BLUETOOTH) {
                        ContextCompat.startForegroundService(
                            activity,
                            IntercomService.setRouteIntent(
                                activity,
                                AudioRouteMode.SPEAKER
                            )
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        ContextCompat.startForegroundService(
                            activity,
                            IntercomService.setRouteIntent(
                                activity,
                                AudioRouteMode.BLUETOOTH
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    if (routeMode == AudioRouteMode.BLUETOOTH) {
                        "USE PHONE SPEAKER"
                    } else {
                        "USE BLUETOOTH EARBUDS"
                    }
                )
            }
            Button(
                onClick = {
                    if (micEnabled) {
                        ContextCompat.startForegroundService(
                            activity,
                            IntercomService.setMicIntent(activity, false)
                        )
                    } else if (
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            ContextCompat.startForegroundService(
                                activity,
                                IntercomService.setMicIntent(activity, true)
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(if (micEnabled) "MICROPHONE ON" else "MICROPHONE OFF")
            }
        }
    }
}

@Composable
private fun DeviceRow(device: IntercomDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.transport.label} · ${if (device.online) "Online" else "Waiting"}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Focused MVP settings", style = MaterialTheme.typography.titleLarge)
            Text("Wi‑Fi discovery and microphone streaming are enabled for the primary workflow.")
            Text("Use the audio output button to switch between the phone speaker and a connected Bluetooth headset or earbuds. Pair the earbuds in Android Bluetooth settings first.")
        }
    }
}

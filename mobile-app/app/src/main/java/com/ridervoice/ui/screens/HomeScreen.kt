package com.ridervoice.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.ui.components.ProfileDrawer
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.utils.OEMBatteryWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onHostRideClick: () -> Unit,
    onJoinRideClick: () -> Unit,
    onStartRideClick: (String) -> Unit,
    onSquadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRoutePlannerClick: () -> Unit,
    onRideHistoryClick: () -> Unit,
    onDeviceSetupClick: () -> Unit,
    onInvitesInboxClick: () -> Unit = { onJoinRideClick() },
    onSosClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onLogoutSuccess: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showBatteryWarning by remember { mutableStateOf(OEMBatteryWarning.isAggressiveOEM()) }

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceState()
        viewModel.refreshActiveRideState()
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = {
                Text(
                    text = "DISENGAGE TRANSCEIVER?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out of RiderVoice? You will need to sign in again to access your squad and convoys.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirmDialog = false
                        viewModel.clearActiveRide()
                        onLogoutSuccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("SIGN OUT", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("CANCEL", color = TextPrimary)
                }
            },
            containerColor = DarkSlate
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent,
                drawerShape = RoundedCornerShape(0.dp)
            ) {
                ProfileDrawer(
                    onAccountClick = {
                        scope.launch { drawerState.close() }
                        onAccountClick()
                    },
                    onSettingsClick = onSettingsClick,
                    onDeviceSetupClick = onDeviceSetupClick,
                    onLogoutClick = {
                        scope.launch { drawerState.close() }
                        showLogoutConfirmDialog = true
                    }
                )
            }
        },
        gesturesEnabled = true
    ) {
        Box(modifier = Modifier.fillMaxSize().background(GraphiteBase)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(TechGreen))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TRANSCEIVER DASHBOARD",
                                color = NeonOrange,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.5.sp
                            )
                        }
                        Text(
                            text = "RIDERVOICE",
                            color = TextPrimary,
                            style = MaterialTheme.typography.displayLarge,
                            fontSize = 32.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(DarkSlate)
                            .border(1.5.dp, BorderColor, CircleShape)
                            .clickable {
                                scope.launch { drawerState.open() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Open Profile Drawer",
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (showBatteryWarning) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = HazardContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.BatteryAlert, contentDescription = "Battery Warning", tint = WarningAmber, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Battery restrictions may mute background voice. Please disable optimization.",
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showBatteryWarning = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    color = if (uiState.hasActiveRide) DarkSlate else DarkSlate.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (uiState.hasActiveRide) NeonOrange else BorderColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (uiState.hasActiveRide && uiState.activeRideName.isNotBlank()) {
                                Modifier.clickable { onStartRideClick(uiState.activeRideName) }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CHANNEL FREQUENCY MONITOR",
                                color = if (uiState.hasActiveRide) NeonOrange else TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.sp
                            )
                            if (uiState.hasActiveRide) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TechGreen))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "LIVE",
                                        color = TechGreen,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (uiState.hasActiveRide) uiState.activeRideName.uppercase() else "NO ACTIVE CONVOY",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 20.sp
                        )

                        Text(
                            text = if (uiState.hasActiveRide) {
                                "${uiState.activeRideSubtitle} • Tap to enter voice HUD"
                            } else {
                                "Tap 'Host Convoy Ride' or 'Join' below to link with riders"
                            },
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "TACTICAL CHANNELS & CONTROLS",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        SleekActionRow("HOST CONVOY RIDE", Icons.Default.PlayArrow, NeonOrange) { onHostRideClick() }
                    }
                    item {
                        SleekActionRow("INVITES INBOX", Icons.Default.Mail, ElectricCyan) { onInvitesInboxClick() }
                    }
                    item {
                        SleekActionRow("JOIN VIA ROOM CODE", Icons.Default.MeetingRoom, ElectricCyan) { onJoinRideClick() }
                    }
                    item {
                        SleekActionRow("ROUTE PLANNER", Icons.Default.Map, NeonViolet) { onRoutePlannerClick() }
                    }
                    item {
                        SleekActionRow("MY SQUAD DIRECTORY", Icons.Default.Group, TechGreen) { onSquadClick() }
                    }
                    item {
                        SleekActionRow("RIDE TELEMETRY HISTORY", Icons.Default.History, TextSecondary) { onRideHistoryClick() }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = HazardContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, AlertRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clickable { onSosClick() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Emergency SOS",
                            tint = AlertRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "EMERGENCY SOS TRANSMIT",
                            color = AlertRed,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SleekActionRow(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Gunmetal)
                    .border(1.dp, BorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

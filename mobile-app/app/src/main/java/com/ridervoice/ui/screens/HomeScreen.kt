package com.ridervoice.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import com.ridervoice.utils.OEMBatteryWarning
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.ui.components.ProfileDrawer

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
    onLogoutSuccess: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (OEMBatteryWarning.isAggressiveOEM()) {
            val warning = OEMBatteryWarning.getBatteryOptimizationWarningText()
            Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
        }
        viewModel.refreshDeviceState()
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text("Log Out", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to log out of RiderVoice?", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmDialog = false
                        onLogoutSuccess()
                    }
                ) {
                    Text("Log Out", color = AlertRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
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
                    .padding(24.dp)
            ) {
                // Top Header with Profile Avatar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "HEY, RIDER",
                            color = NeonViolet, // Premium HUD aesthetic
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "READY TO RIDE?",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    
                    // Profile Avatar Button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(DarkSlate)
                            .border(1.dp, Gunmetal, CircleShape)
                            .clickable {
                                scope.launch { drawerState.open() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Active Ride Panel (Glassmorphic)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(DarkSlate.copy(alpha = 0.8f))
                        .border(1.dp, Gunmetal, RoundedCornerShape(24.dp))
                        .clickable { 
                            if (uiState.hasActiveRide && uiState.activeRideName.isNotBlank()) {
                                onStartRideClick(uiState.activeRideName) 
                            }
                        }
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = if (uiState.hasActiveRide) uiState.activeRideName else "No Active Ride",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = uiState.activeRideSubtitle,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "ACTIONS",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Sleek Vertical List instead of Grid
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item { SleekActionRow("HOST RIDE", Icons.Default.PlayArrow, NeonOrange) { onHostRideClick() } }
                    item { SleekActionRow("JOIN RIDE", Icons.Default.GroupAdd, ElectricCyan) { onJoinRideClick() } }
                    item { SleekActionRow("ROUTE PLANNER", Icons.Default.Map, NeonViolet) { onRoutePlannerClick() } }
                    item { SleekActionRow("MY SQUAD", Icons.Default.Group, TextSecondary) { onSquadClick() } }
                    item { SleekActionRow("RIDE HISTORY", Icons.Default.History, TextSecondary) { onRideHistoryClick() } }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSlate.copy(alpha = 0.5f))
            .border(1.dp, Gunmetal, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(GraphiteSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
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

package com.ridervoice.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.ConfirmDialog
import com.ridervoice.ui.components.MoreMenuBottomSheet
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.utils.OEMBatteryWarning

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

    var showProfileSheet by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showBatteryWarning by remember { mutableStateOf(OEMBatteryWarning.isAggressiveOEM()) }

    val userInitial = remember {
        try {
            val u = FirebaseAuth.getInstance().currentUser
            val name = u?.displayName?.takeIf { it.isNotBlank() } ?: u?.email ?: "R"
            name.trim().firstOrNull()?.uppercase() ?: "R"
        } catch (_: Exception) {
            "R"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceState()
        viewModel.refreshActiveRideState()
    }

    if (showProfileSheet) {
        MoreMenuBottomSheet(
            onDismiss = { showProfileSheet = false },
            onAccountClick = onAccountClick,
            onSettingsClick = onSettingsClick,
            onDeviceSetupClick = onDeviceSetupClick,
            onRoutePlannerClick = onRoutePlannerClick,
            onInvitesInboxClick = onInvitesInboxClick,
            onSignOutClick = { showLogoutConfirmDialog = true }
        )
    }

    if (showLogoutConfirmDialog) {
        ConfirmDialog(
            title = "Sign out of RiderVoice?",
            body = "Are you sure you want to log out? You will need to sign in again to access your squad and group rides.",
            confirmLabel = "Sign Out",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                showLogoutConfirmDialog = false
                viewModel.clearActiveRide()
                onLogoutSuccess()
            },
            onDismiss = { showLogoutConfirmDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        // Scrollable Bento Dashboard Body
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Bar: "Dashboard" + Circular Profile Avatar with Moss Online Dot
            CapChromeFontScale(maxScale = 1.3f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dashboard",
                        color = Ink,
                        style = MaterialTheme.typography.displayLarge,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Box(
                        modifier = Modifier
                            .size(RvTouchTarget.min)
                            .clickable { showProfileSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(CharcoalSurface)
                                .border(1.5.dp, InkHairline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userInitial,
                                color = PaperOnPop,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(Moss)
                                .border(2.dp, Paper, CircleShape)
                        )
                    }
                }
            }

            // Optional OEM Battery Warning Banner
            if (showBatteryWarning) {
                Surface(
                    color = PaperSunken,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = "Battery Warning",
                            tint = Pop,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Battery restrictions may mute background voice. Disable optimization for reliable audio.",
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showBatteryWarning = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = InkSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 1. HERO BENTO CARD — "Create a ride" with motorcycle photo background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, InkHairline, RoundedCornerShape(28.dp))
                    .clickable { onHostRideClick() }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hero_create_ride),
                    contentDescription = "Create a ride",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Warm terracotta-espresso editorial scrim for high contrast white typography
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xDE4A1E0E),
                                    Color(0x9E3D1C10),
                                    Color(0x4D1E1510)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.76f)) {
                        Text(
                            text = "CONVOY LEAD",
                            color = Color(0xE6FFFDF8),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Create a ride",
                            color = Color(0xFFFFFDF8),
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start a convoy and invite your squad",
                            color = Color(0xE0FFFDF8),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            lineHeight = 19.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFFDF8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Start creating ride",
                                tint = Color(0xFF23201B),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // 2. 2-COLUMN BENTO ROW — "Join a ride" (Sand) & "Inbox" (Paper)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Sand Tile: Join a ride
                Surface(
                    color = PaperSunken,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 142.dp)
                        .clickable { onJoinRideClick() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(CharcoalSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = "Join a ride",
                                tint = PaperOnPop,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(26.dp))
                        Column {
                            Text(
                                text = "Join a ride",
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Code or link",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Right Paper Tile: Inbox
                Surface(
                    color = PaperRaised,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, InkHairline),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 142.dp)
                        .clickable { onInvitesInboxClick() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PaperSunken),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsNone,
                                    contentDescription = "Inbox",
                                    tint = Ink,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(26.dp))
                        Column {
                            Text(
                                text = "Inbox",
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Ride invitations",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 3. LIVE / ACTIVE CONVOY CARD (Matches Image 1 Bottom Bento Card)
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    width = if (uiState.hasActiveRide) 1.5.dp else 1.dp,
                    color = if (uiState.hasActiveRide) Pop.copy(alpha = 0.45f) else InkHairline
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.hasActiveRide) Moss else InkDisabled)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = if (uiState.hasActiveRide) "LIVE CONVOY" else "CONVOY STATUS",
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp
                            )
                        }
                        if (uiState.hasActiveRide) {
                            Surface(
                                color = MossContainer,
                                shape = RoundedCornerShape(RvRadius.pill)
                            ) {
                                Text(
                                    text = "Audio Live",
                                    color = Moss,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (uiState.hasActiveRide) uiState.activeRideName else "No active convoy",
                        color = Ink,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.hasActiveRide) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = if (uiState.hasActiveRide) {
                                uiState.activeRideSubtitle
                            } else {
                                "Create a ride or join with a code to connect with your group."
                            },
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp
                        )
                    }

                    if (uiState.hasActiveRide) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = PaperSunken,
                                shape = RoundedCornerShape(RvRadius.pill)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HeadsetMic,
                                        contentDescription = null,
                                        tint = Ink,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = uiState.deviceName,
                                        color = Ink,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (uiState.activeRideName.isNotBlank()) {
                                        onStartRideClick(uiState.activeRideName)
                                    }
                                },
                                shape = RoundedCornerShape(RvRadius.pill),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Pop,
                                    contentColor = PaperOnPop
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text(
                                    text = "Join Convoy",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pinned Emergency SOS Bar directly above the bottom navigation bar
        CapChromeFontScale(maxScale = 1.3f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Paper)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Surface(
                    color = BrickContainer,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, Brick),
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
                            tint = Brick,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "EMERGENCY SOS",
                            color = Brick,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
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
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (emphasized) PopContainer else PaperSunken)
                    .border(1.dp, if (emphasized) Pop else InkHairline, CircleShape),
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
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = InkSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

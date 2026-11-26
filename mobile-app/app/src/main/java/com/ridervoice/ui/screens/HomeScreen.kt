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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.ui.components.CapChromeFontScale
import com.ridervoice.ui.components.TabProfileAvatar
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.utils.OEMBatteryWarning
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

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
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .rvTopSafeArea()
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
                        text = stringResource(R.string.tab_dashboard),
                        color = Ink,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )

                    TabProfileAvatar(
                        userInitial = userInitial,
                        onClick = onAccountClick
                    )
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
                            RvIcons.BatteryAlert,
                            contentDescription = stringResource(R.string.cd_battery_warning),
                            tint = Pop,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.home_battery_warning_body),
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showBatteryWarning = false },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                RvIcons.Close,
                                contentDescription = stringResource(R.string.cd_dismiss),
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
                    .clickable(role = Role.Button) { onHostRideClick() }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hero_create_ride),
                    contentDescription = stringResource(R.string.home_create_ride_title),
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
                                    HeroPhotoScrimTop,
                                    HeroPhotoScrimMid,
                                    HeroPhotoScrimBottom
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
                            text = stringResource(R.string.home_create_ride_title),
                            color = HeroPhotoTextPrimary,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_create_ride_subtitle),
                            color = HeroPhotoTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
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
                                .background(HeroPhotoBadgeFill),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = RvIcons.Forward,
                                contentDescription = stringResource(R.string.cd_start_creating_ride),
                                tint = HeroPhotoBadgeIcon,
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
                        .clickable(role = Role.Button) { onJoinRideClick() }
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
                                imageVector = RvIcons.Login,
                                contentDescription = stringResource(R.string.action_join_ride),
                                tint = PaperOnPop,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(26.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.action_join_ride),
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.home_join_subtitle),
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
                        .clickable(role = Role.Button) { onInvitesInboxClick() }
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
                                    imageVector = RvIcons.NotificationsNone,
                                    contentDescription = stringResource(R.string.menu_inbox_title),
                                    tint = Ink,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Icon(
                                imageVector = RvIcons.ChevronRight,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(26.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.menu_inbox_title),
                                color = Ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.home_inbox_subtitle),
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
                            Modifier.clickable(role = Role.Button) { onStartRideClick(uiState.activeRideName) }
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
                                    .background(if (uiState.hasActiveRide) Moss else InkSecondary)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = if (uiState.hasActiveRide) stringResource(R.string.home_live_convoy) else stringResource(R.string.home_convoy_status),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp,
                                modifier = Modifier.semantics { heading() }
                            )
                        }
                        if (uiState.hasActiveRide) {
                            Surface(
                                color = MossContainer,
                                shape = RoundedCornerShape(RvRadius.pill)
                            ) {
                                Text(
                                    text = stringResource(R.string.home_audio_live),
                                    color = Moss,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (uiState.hasActiveRide) uiState.activeRideName else stringResource(R.string.home_no_active_convoy),
                        color = Ink,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.hasActiveRide) {
                            Icon(
                                imageVector = RvIcons.Mic,
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
                                stringResource(R.string.home_no_active_convoy_desc)
                            },
                            color = InkSecondary,
                            style = MaterialTheme.typography.titleSmall
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
                                        imageVector = RvIcons.HeadsetMic,
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
                                    text = stringResource(R.string.home_join_convoy),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = RvIcons.Forward,
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
                        .clickable(role = Role.Button) { onSosClick() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = RvIcons.Warning,
                            contentDescription = stringResource(R.string.sos_title),
                            tint = Brick,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.home_emergency_sos_upper),
                            color = Brick,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

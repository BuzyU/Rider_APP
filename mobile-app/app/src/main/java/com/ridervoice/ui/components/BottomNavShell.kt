package com.ridervoice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridervoice.navigation.Routes
import com.ridervoice.ui.theme.*

/**
 * Caps fontScale for fixed non-scrolling chrome (bottom navigation, compact headers)
 * while leaving scrollable content uncapped for accessibility.
 */
@Composable
fun CapChromeFontScale(
    maxScale: Float = 1.3f,
    content: @Composable () -> Unit
) {
    val current = LocalDensity.current
    val capped = current.fontScale.coerceAtMost(maxScale)
    CompositionLocalProvider(
        LocalDensity provides Density(density = current.density, fontScale = capped),
        content = content
    )
}

/**
 * 4-tab Roadside Paper Bottom Navigation Bar matching the Dashboard/History design:
 * Tabs: Dashboard | History | My Squad | Settings
 * Active tab houses the icon inside a 64x32dp Ember-tint pill (#F3DDD2).
 */
@Composable
fun RvBottomNavigationBar(
    currentRoute: String?,
    onHomeClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSquadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CapChromeFontScale(maxScale = 1.3f) {
        Surface(
            color = PaperRaised,
            modifier = modifier
                .fillMaxWidth()
                .testTag("rv_bottom_nav_bar")
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                Divider(color = InkHairline, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavTabItem(
                        label = "Dashboard",
                        icon = Icons.Default.GridView,
                        selected = currentRoute == Routes.HOME,
                        onClick = onHomeClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_home")
                    )
                    BottomNavTabItem(
                        label = "History",
                        icon = Icons.Default.History,
                        selected = currentRoute == Routes.RIDE_STATS,
                        onClick = onHistoryClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_history")
                    )
                    BottomNavTabItem(
                        label = "My Squad",
                        icon = Icons.Default.Group,
                        selected = currentRoute == Routes.SQUAD,
                        onClick = onSquadClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_squad")
                    )
                    BottomNavTabItem(
                        label = "Settings",
                        icon = Icons.Default.Tune,
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = onSettingsClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_settings")
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint = if (selected) Ink else InkDisabled
    val labelColor = if (selected) Ink else InkSecondary

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = RvTouchTarget.min, minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(RvRadius.pill))
                .background(if (selected) PopContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Material3 1.0.1 compatible bottom-anchored sheet opened from the Profile Avatar on Dashboard.
 * Preserves 100% callback parity (Account, Settings, Audio & Helmet Setup, Route Planner, Sign Out).
 */
@Composable
fun MoreMenuBottomSheet(
    onDismiss: () -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeviceSetupClick: () -> Unit,
    onRoutePlannerClick: (() -> Unit)? = null,
    onInvitesInboxClick: (() -> Unit)? = null,
    onSignOutClick: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(InkHairline)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rider Menu",
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 20.sp,
                            color = Ink
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(RvTouchTarget.min)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close menu",
                                tint = InkSecondary
                            )
                        }
                    }

                    MoreSheetRow(
                        icon = Icons.Default.AccountCircle,
                        title = "Account & Profile",
                        subtitle = "Callsign, motorcycle & details",
                        onClick = {
                            onDismiss()
                            onAccountClick()
                        }
                    )
                    MoreSheetRow(
                        icon = Icons.Default.HeadsetMic,
                        title = "Audio & Helmet Setup",
                        subtitle = "Bluetooth intercom & mic routing",
                        onClick = {
                            onDismiss()
                            onDeviceSetupClick()
                        }
                    )
                    if (onRoutePlannerClick != null) {
                        MoreSheetRow(
                            icon = Icons.Default.Map,
                            title = "Route Planner",
                            subtitle = "Plan waypoints & offline radar",
                            onClick = {
                                onDismiss()
                                onRoutePlannerClick()
                            }
                        )
                    }
                    if (onInvitesInboxClick != null) {
                        MoreSheetRow(
                            icon = Icons.Default.Mail,
                            title = "Inbox",
                            subtitle = "Pending group ride invitations",
                            onClick = {
                                onDismiss()
                                onInvitesInboxClick()
                            }
                        )
                    }
                    MoreSheetRow(
                        icon = Icons.Default.Tune,
                        title = "Settings",
                        subtitle = "Voice, language, display & updates",
                        onClick = {
                            onDismiss()
                            onSettingsClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = InkHairline, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(2.dp))

                    MoreSheetRow(
                        icon = Icons.Default.Logout,
                        title = "Sign Out",
                        subtitle = null,
                        destructive = true,
                        onClick = {
                            onDismiss()
                            onSignOutClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val contentTint = if (destructive) Brick else Ink
    val iconBg = if (destructive) BrickContainer else PaperSunken

    Surface(
        color = if (destructive) BrickContainer.copy(alpha = 0.4f) else Paper,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (destructive) Brick else InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentTint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = contentTint,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!destructive) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = InkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

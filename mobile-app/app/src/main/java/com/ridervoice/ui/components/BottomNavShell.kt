package com.ridervoice.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.roundToInt
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.navigation.Routes
import com.ridervoice.ui.theme.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey

val RvBottomNavIconNameKey = SemanticsPropertyKey<String>("RvBottomNavIconName")

private val TOP_LEVEL_TAB_ROUTES = setOf(
    Routes.HOME,
    Routes.RIDE_STATS,
    Routes.SQUAD,
    Routes.SETTINGS
)

/**
 * Bottom navigation bar is shown ONLY on the 4 top-level tab routes and hidden whenever
 * the software keyboard (IME) is open or the user is on any secondary/in-ride screen.
 */
fun shouldShowBottomBar(currentRoute: String?, isImeVisible: Boolean): Boolean {
    return !isImeVisible && currentRoute != null && currentRoute in TOP_LEVEL_TAB_ROUTES
}

/**
 * 48dp touch-target profile avatar rendered in the top-right header of Dashboard to open Account.
 */
@Composable
fun TabProfileAvatar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    userInitial: String? = null
) {
    val resolvedInitial = userInitial ?: remember {
        try {
            val u = FirebaseAuth.getInstance().currentUser
            val name = u?.displayName?.takeIf { it.isNotBlank() } ?: u?.email ?: "R"
            name.trim().firstOrNull()?.uppercase() ?: "R"
        } catch (_: Exception) {
            "R"
        }
    }
    val openAccountCd = stringResource(R.string.nav_account)
    Box(
        modifier = modifier
            .size(RvTouchTarget.min)
            .testTag("tab_profile_avatar")
            .semantics { contentDescription = openAccountCd }
            .clickable(role = Role.Button, onClickLabel = openAccountCd, onClick = onClick),
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
                text = resolvedInitial,
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
    val selectedIndex = when (currentRoute) {
        Routes.HOME -> 0
        Routes.RIDE_STATS -> 1
        Routes.SQUAD -> 2
        Routes.SETTINGS -> 3
        else -> -1
    }
    val reduceMotion = rememberIsReduceMotionEnabled()
    val animatedIndex by animateFloatAsState(
        targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
        animationSpec = if (reduceMotion) tween(durationMillis = RvMotion.INSTANT)
        else spring(dampingRatio = 0.95f, stiffness = 500f)
    )

    val isDark = ThemeState.isDarkTheme
    val dockSurface = if (isDark) PaperRaised else PaperSunken
    val activeThumbColor = if (isDark) PaperSunken else PaperRaised

    CapChromeFontScale(maxScale = 1.3f) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(Paper)
                .rvBottomSafeArea()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("rv_bottom_nav_bar"),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(dockSurface)
                    .border(1.dp, InkHairline, CircleShape)
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val tabCount = 4
                val tabWidth = maxWidth / tabCount

                if (selectedIndex >= 0) {
                    val travelProgress = abs(selectedIndex - animatedIndex).coerceIn(0f, 1f)
                    val basePillWidth = 56.dp.coerceAtMost((tabWidth - 6.dp).coerceAtLeast(36.dp))
                    val pillWidth = (basePillWidth + (12.dp * travelProgress)).coerceAtMost(tabWidth)
                    val density = LocalDensity.current

                    Box(
                        modifier = Modifier
                            .offset {
                                val tabWidthPx = with(density) { tabWidth.toPx() }
                                val pillWidthPx = with(density) { pillWidth.toPx() }
                                val xPx = (tabWidthPx * animatedIndex) + ((tabWidthPx - pillWidthPx) / 2f)
                                IntOffset(x = xPx.roundToInt(), y = 0)
                            }
                            .width(pillWidth)
                            .height(42.dp)
                            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(activeThumbColor)
                            .border(1.dp, InkHairline.copy(alpha = 0.85f), CircleShape)
                            .testTag("bottom_nav_active_pill")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavTabItem(
                        label = stringResource(R.string.tab_dashboard),
                        unselectedIcon = RvIcons.NavHome,
                        selectedIcon = RvIcons.NavHomeSelected,
                        selected = currentRoute == Routes.HOME,
                        onClick = onHomeClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_home")
                    )
                    BottomNavTabItem(
                        label = stringResource(R.string.tab_history),
                        unselectedIcon = RvIcons.NavHistory,
                        selectedIcon = RvIcons.NavHistorySelected,
                        selected = currentRoute == Routes.RIDE_STATS,
                        onClick = onHistoryClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_history")
                    )
                    BottomNavTabItem(
                        label = stringResource(R.string.tab_my_squad),
                        unselectedIcon = RvIcons.NavSquad,
                        selectedIcon = RvIcons.NavSquadSelected,
                        selected = currentRoute == Routes.SQUAD,
                        onClick = onSquadClick,
                        modifier = Modifier.weight(1f).testTag("bottom_nav_squad")
                    )
                    BottomNavTabItem(
                        label = stringResource(R.string.nav_settings),
                        unselectedIcon = RvIcons.NavSettings,
                        selectedIcon = RvIcons.NavSettingsSelected,
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
    unselectedIcon: ImageVector,
    selectedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reduceMotion = rememberIsReduceMotionEnabled()
    val iconTint by animateColorAsState(
        targetValue = if (selected) Pop else InkSecondary,
        animationSpec = tween(
            durationMillis = if (reduceMotion) RvMotion.INSTANT else RvMotion.STANDARD,
            easing = RvMotion.Easing
        )
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = if (reduceMotion) tween(durationMillis = RvMotion.INSTANT)
        else spring(dampingRatio = 0.95f, stiffness = 500f)
    )
    val iconLiftDp by animateFloatAsState(
        targetValue = if (selected) -1f else 0f,
        animationSpec = if (reduceMotion) tween(durationMillis = RvMotion.INSTANT)
        else spring(dampingRatio = 0.95f, stiffness = 500f)
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = RvTouchTarget.min, minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics {
                this[RvBottomNavIconNameKey] = (if (selected) selectedIcon else unselectedIcon).name
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier
                .size(23.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    translationY = iconLiftDp * density
                }
        )
    }
}

/**
 * Material3 1.0.1 compatible bottom-anchored sheet opened from the Profile Avatar on Dashboard.
 * Preserves 100% callback parity (Account, Settings, Audio & Helmet Setup, Route Planner, Sign Out).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RvScrim)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.cd_dismiss),
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .rvBottomSafeArea()
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
                            text = stringResource(R.string.menu_rider_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(RvTouchTarget.min)
                        ) {
                            Icon(
                                imageVector = RvIcons.Close,
                                contentDescription = stringResource(R.string.cd_close_menu),
                                tint = InkSecondary
                            )
                        }
                    }

                    MoreSheetRow(
                        icon = RvIcons.AccountCircle,
                        title = stringResource(R.string.menu_account_title),
                        subtitle = stringResource(R.string.menu_account_subtitle),
                        onClick = {
                            onDismiss()
                            onAccountClick()
                        }
                    )
                    MoreSheetRow(
                        icon = RvIcons.HeadsetMic,
                        title = stringResource(R.string.menu_audio_title),
                        subtitle = stringResource(R.string.menu_audio_subtitle),
                        onClick = {
                            onDismiss()
                            onDeviceSetupClick()
                        }
                    )
                    if (onRoutePlannerClick != null) {
                        MoreSheetRow(
                            icon = RvIcons.Map,
                            title = stringResource(R.string.route_planner_title),
                            subtitle = stringResource(R.string.menu_route_planner_subtitle),
                            onClick = {
                                onDismiss()
                                onRoutePlannerClick()
                            }
                        )
                    }
                    if (onInvitesInboxClick != null) {
                        MoreSheetRow(
                            icon = RvIcons.Mail,
                            title = stringResource(R.string.menu_inbox_title),
                            subtitle = stringResource(R.string.menu_inbox_subtitle),
                            onClick = {
                                onDismiss()
                                onInvitesInboxClick()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(
                        color = InkHairline,
                        thickness = 1.dp,
                        modifier = Modifier.testTag("more_menu_sign_out_divider")
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    MoreSheetRow(
                        icon = RvIcons.Logout,
                        title = stringResource(R.string.action_sign_out),
                        subtitle = null,
                        destructive = true,
                        modifier = Modifier.testTag("more_menu_sign_out"),
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val contentTint = if (destructive) Brick else Ink
    val iconBg = if (destructive) BrickContainer else PaperSunken

    Surface(
        color = if (destructive) BrickContainer.copy(alpha = 0.4f) else Paper,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (destructive) Brick else InkHairline),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
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
                    style = MaterialTheme.typography.bodyLarge,
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
                    imageVector = RvIcons.ChevronRight,
                    contentDescription = null,
                    tint = InkSecondary,
                    modifier = Modifier.size(18.dp).rvRtlMirrored()
                )
            }
        }
    }
}

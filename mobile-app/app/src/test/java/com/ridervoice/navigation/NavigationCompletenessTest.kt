package com.ridervoice.navigation

import android.app.Application
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.components.MoreMenuBottomSheet
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.components.TabProfileAvatar
import com.ridervoice.ui.components.shouldShowBottomBar
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.theme.RvMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-U4 Navigation Completeness Verification:
 * - Bottom bar visible ONLY on the 4 top-level tabs and hidden when IME is open or on non-tab routes.
 * - Profile avatar opens MoreMenuBottomSheet on all 4 tab screens (Dashboard, History, My Squad, Settings).
 * - Sign Out row in MoreMenuBottomSheet has >= 16dp clear separation below the divider.
 * - Bottom bar items have >= 48dp touch targets and are clickable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class NavigationCompletenessTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun locateJavaRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/ridervoice"),
            File("app/src/main/java/com/ridervoice"),
            File("mobile-app/app/src/main/java/com/ridervoice")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not find com/ridervoice source root")
    }

    @Test
    fun onlyDashboardRendersProfileAvatar_andOpensAccountDirectly_andNavGraphHidesBarOnIme() {
        val root = locateJavaRoot()
        val homeContent = File(root, "ui/screens/HomeScreen.kt").readText()
        assertTrue(
            "Dashboard (HomeScreen.kt) must render TabProfileAvatar opening Account directly",
            homeContent.contains("TabProfileAvatar(") &&
                homeContent.contains("onClick = onAccountClick") &&
                !homeContent.contains("MoreMenuBottomSheet(")
        )

        val nonDashboardScreens = listOf(
            "ui/screens/RideStatsScreen.kt",
            "ui/screens/SquadScreen.kt",
            "ui/screens/SettingsScreen.kt"
        )
        val unexpectedAvatar = nonDashboardScreens.filter { rel ->
            File(root, rel).readText().contains("TabProfileAvatar(")
        }
        assertTrue(
            "Only Dashboard should render TabProfileAvatar; found unexpectedly in: $unexpectedAvatar",
            unexpectedAvatar.isEmpty()
        )

        val navGraph = File(root, "navigation/NavGraph.kt").readText()
        assertTrue(
            "NavGraph must check IME visibility to hide bottom bar when keyboard is open",
            navGraph.contains("WindowInsets.ime") && navGraph.contains("shouldShowBottomBar(")
        )
    }

    @Test
    fun moreMenuBottomSheet_hasAtLeast16dpClearGapAboveSignOut() {
        var signOutClicked = false
        composeRule.setContent {
            RiderVoiceTheme {
                MoreMenuBottomSheet(
                    onDismiss = {},
                    onAccountClick = {},
                    onSettingsClick = {},
                    onDeviceSetupClick = {},
                    onRoutePlannerClick = {},
                    onInvitesInboxClick = {},
                    onSignOutClick = { signOutClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        val dividerNode = composeRule.onNodeWithTag("more_menu_sign_out_divider", useUnmergedTree = true)
        val signOutNode = composeRule.onNodeWithTag("more_menu_sign_out", useUnmergedTree = true)

        dividerNode.assertIsDisplayed()
        signOutNode.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)

        val dividerBounds = dividerNode.getBoundsInRoot()
        val signOutBounds = signOutNode.getBoundsInRoot()
        val clearGap = signOutBounds.top - dividerBounds.bottom
        assertTrue(
            "Sign Out row must have >= 16dp clear space below divider, but was $clearGap",
            clearGap >= 16.dp
        )

        signOutNode.performClick()
        assertTrue("Sign Out click callback must fire", signOutClicked)
    }

    @Test
    fun bottomNavigationBar_tabsHaveValidTargetsAndCallbacks() {
        var clicked = ""
        composeRule.setContent {
            RiderVoiceTheme {
                RvBottomNavigationBar(
                    currentRoute = Routes.HOME,
                    onHomeClick = { clicked = "home" },
                    onHistoryClick = { clicked = "history" },
                    onSquadClick = { clicked = "squad" },
                    onSettingsClick = { clicked = "settings" }
                )
            }
        }
        composeRule.waitForIdle()

        val tags = listOf(
            "bottom_nav_home" to "home",
            "bottom_nav_history" to "history",
            "bottom_nav_squad" to "squad",
            "bottom_nav_settings" to "settings"
        )
        for ((tag, expected) in tags) {
            val node = composeRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            node.performClick()
            assertTrue("Clicking $tag should trigger $expected", clicked == expected)
        }
        composeRule.onNodeWithText("Dashboard").assertDoesNotExist()
        composeRule.onNodeWithText("History").assertDoesNotExist()
        composeRule.onNodeWithText("My Squad").assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun bottomNavigationBar_activePillSlidesSmoothlyBetweenTabs() {
        var activeRoute by mutableStateOf(Routes.HOME)
        composeRule.setContent {
            RiderVoiceTheme {
                RvBottomNavigationBar(
                    currentRoute = activeRoute,
                    onHomeClick = { activeRoute = Routes.HOME },
                    onHistoryClick = { activeRoute = Routes.RIDE_STATS },
                    onSquadClick = { activeRoute = Routes.SQUAD },
                    onSettingsClick = { activeRoute = Routes.SETTINGS }
                )
            }
        }
        composeRule.waitForIdle()

        val pillAtHome = composeRule.onNodeWithTag("bottom_nav_active_pill", useUnmergedTree = true)
            .assertIsDisplayed()
            .getBoundsInRoot()
        val homeTabBounds = composeRule.onNodeWithTag("bottom_nav_home").getBoundsInRoot()

        activeRoute = Routes.SETTINGS
        composeRule.waitForIdle()

        val pillAtSettings = composeRule.onNodeWithTag("bottom_nav_active_pill", useUnmergedTree = true)
            .assertIsDisplayed()
            .getBoundsInRoot()
        val settingsTabBounds = composeRule.onNodeWithTag("bottom_nav_settings").getBoundsInRoot()

        assertTrue(
            "Pill should start centered inside Dashboard tab: $pillAtHome vs $homeTabBounds",
            pillAtHome.left >= homeTabBounds.left && pillAtHome.right <= homeTabBounds.right
        )
        assertTrue(
            "Pill should slide to Settings tab: $pillAtSettings vs $settingsTabBounds",
            pillAtSettings.left >= settingsTabBounds.left && pillAtSettings.right <= settingsTabBounds.right
        )
        assertTrue(
            "Pill X position must move right when navigating from Dashboard to Settings",
            pillAtSettings.left > pillAtHome.right
        )
    }

    @Test
    fun shouldShowBottomBar_onlyOnTopLevelTabsAndHiddenWhenImeOpen() {
        val topLevelRoutes = listOf(Routes.HOME, Routes.RIDE_STATS, Routes.SQUAD, Routes.SETTINGS)
        val nonTabRoutes = listOf(
            null,
            Routes.SPLASH,
            Routes.LOGIN,
            Routes.REGISTER,
            Routes.HOST_SETUP,
            Routes.LOBBY,
            Routes.INVITES_INBOX,
            Routes.DEVICE_SETUP,
            Routes.ACTIVE_RIDE_HUD,
            Routes.SOS,
            Routes.POST_RIDE_SUMMARY,
            Routes.ACCOUNT,
            Routes.SQUAD_REQUESTS,
            Routes.ROUTE_PLANNER,
            Routes.JOIN_ROOM,
            Routes.JOIN_VIA_TOKEN,
            Routes.RIDE_REPLAY,
            Routes.OFFLINE_MESH_LOBBY,
            Routes.OFFLINE_MESH_HUD
        )

        val shellFile = File(locateJavaRoot(), "ui/components/BottomNavShell.kt").readText()
        assertTrue(
            "BottomNavShell.kt must define shouldShowBottomBar(currentRoute, isImeVisible)",
            shellFile.contains("fun shouldShowBottomBar(")
        )
        for (route in topLevelRoutes) {
            assertTrue("Bottom bar must be shown on $route when IME closed", shouldShowBottomBar(route, isImeVisible = false))
            assertFalse("Bottom bar must be hidden on $route when IME open", shouldShowBottomBar(route, isImeVisible = true))
        }
        for (route in nonTabRoutes) {
            assertFalse("Bottom bar must be hidden on non-tab route $route", shouldShowBottomBar(route, isImeVisible = false))
            assertFalse("Bottom bar must be hidden on non-tab route $route with IME", shouldShowBottomBar(route, isImeVisible = true))
        }
    }

    @Test
    fun tabProfileAvatar_has48dpTouchTargetAndFiresCallback() {
        var opened = false
        composeRule.setContent {
            RiderVoiceTheme {
                TabProfileAvatar(userInitial = "U", onClick = { opened = true })
            }
        }
        composeRule.waitForIdle()
        val avatar = composeRule.onNodeWithTag("tab_profile_avatar")
        avatar.assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        avatar.performClick()
        assertTrue("Tapping TabProfileAvatar must invoke onClick", opened)
    }

    @Test
    fun allNavGraphRoutes_haveExplicitRvMotionPolicy_andRespectReduceMotion() {
        val root = locateJavaRoot()
        val navGraphText = File(root, "navigation/NavGraph.kt").readText()
        val routesText = File(root, "navigation/Routes.kt").readText()

        // Every constant in Routes must exist in RvMotion.allRoutePolicies
        val routeConstRegex = Regex("""const val\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"""")
        val definedRoutes = routeConstRegex.findAll(routesText)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("Expected defined routes in Routes.kt", definedRoutes.isNotEmpty())
        for ((constName, routePattern) in definedRoutes) {
            assertTrue(
                "Routes.$constName ($routePattern) missing from RvMotion.allRoutePolicies",
                RvMotion.allRoutePolicies.containsKey(routePattern)
            )
        }

        // Every composable(...) in NavGraph.kt must resolve to an explicit policy in RvMotion.allRoutePolicies
        val composableRouteRegex = Regex("""composable\(\s*(?:route\s*=\s*)?Routes\.([A-Z0-9_]+)""")
        val navGraphConsts = composableRouteRegex.findAll(navGraphText)
            .map { it.groupValues[1] }
            .toSet()
        val routeMap = definedRoutes.toMap()
        assertTrue("Expected composable() routes in NavGraph.kt", navGraphConsts.isNotEmpty())
        for (constName in navGraphConsts) {
            val pattern = routeMap[constName]
            assertTrue("NavGraph references unknown Routes.$constName", pattern != null)
            assertTrue(
                "NavGraph route Routes.$constName must be covered by RvMotion.allRoutePolicies",
                RvMotion.allRoutePolicies.containsKey(pattern)
            )
        }

        // NavHost in NavGraph.kt must wire all 4 transition lambdas to RvMotion
        assertTrue(navGraphText.contains("enterTransition ="))
        assertTrue(navGraphText.contains("exitTransition ="))
        assertTrue(navGraphText.contains("popEnterTransition ="))
        assertTrue(navGraphText.contains("popExitTransition ="))
        assertTrue(navGraphText.contains("RvMotion.enterTransition"))

        // Verify policies: Ride-critical -> None; Top-level -> None; Drill-down -> non-None slide+fade
        val rideCritical = listOf(
            Routes.ACTIVE_RIDE_HUD,
            Routes.SOS,
            Routes.OFFLINE_MESH_LOBBY,
            Routes.OFFLINE_MESH_HUD
        )
        for (route in rideCritical) {
            assertEquals(
                EnterTransition.None,
                RvMotion.enterTransition(Routes.HOME, route, reduceMotion = false)
            )
            assertEquals(
                ExitTransition.None,
                RvMotion.exitTransition(Routes.HOME, route, reduceMotion = false)
            )
            assertEquals(
                EnterTransition.None,
                RvMotion.popEnterTransition(route, Routes.HOME, reduceMotion = false)
            )
            assertEquals(
                ExitTransition.None,
                RvMotion.popExitTransition(route, Routes.HOME, reduceMotion = false)
            )
        }

        val topLevel = listOf(Routes.HOME, Routes.SQUAD, Routes.ACCOUNT, Routes.SETTINGS, Routes.RIDE_STATS)
        for (route in topLevel) {
            assertEquals(
                EnterTransition.None,
                RvMotion.enterTransition(Routes.HOME, route, reduceMotion = false)
            )
        }

        val drillDown = listOf(Routes.HOST_SETUP, Routes.LOBBY, Routes.POST_RIDE_SUMMARY, Routes.ROUTE_PLANNER)
        for (route in drillDown) {
            assertNotEquals(
                "Drill-down route $route should animate when reduceMotion = false",
                EnterTransition.None,
                RvMotion.enterTransition(Routes.HOME, route, reduceMotion = false)
            )
            assertEquals(
                "Drill-down route $route must become None when reduceMotion = true",
                EnterTransition.None,
                RvMotion.enterTransition(Routes.HOME, route, reduceMotion = true)
            )
            assertEquals(
                ExitTransition.None,
                RvMotion.exitTransition(Routes.HOME, route, reduceMotion = true)
            )
        }

        // System Settings.Global.ANIMATOR_DURATION_SCALE == 0f turns reduceMotion on
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            assertTrue(
                "ANIMATOR_DURATION_SCALE == 0f must enable reduceMotion",
                RvMotion.isReduceMotionEnabled(ctx)
            )
        } finally {
            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun bottomNavShell_usesNonOvershootingMotion() {
        val shellText = File(locateJavaRoot(), "ui/components/BottomNavShell.kt").readText()
        val dampingRegex = Regex("""dampingRatio\s*=\s*([0-9.]+)f""")
        val ratios = dampingRegex.findAll(shellText).map { it.groupValues[1].toFloat() }.toList()
        assertTrue("Expected spring damping ratios in BottomNavShell", ratios.isNotEmpty())
        for (ratio in ratios) {
            assertTrue("BottomNavShell spring dampingRatio=$ratio must be >= 0.9f", ratio >= 0.9f)
        }
    }
}

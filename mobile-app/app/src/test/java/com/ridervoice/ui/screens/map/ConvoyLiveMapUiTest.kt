package com.ridervoice.ui.screens.map

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.android.gms.maps.model.LatLng
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp", application = Application::class)
class ConvoyLiveMapUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun englishContext() = RuntimeEnvironment.getApplication().let { base ->
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        base.createConfigurationContext(cfg)
    }

    private fun sampleState(
        selectedRiderId: String? = "r2",
        hasPermission: Boolean = true
    ): ConvoyMapUiState {
        val now = System.currentTimeMillis()
        return ConvoyMapUiState(
            rideName = "Lonavala Dawn Ride Extra Long Title Check",
            isOnlineVoiceConnected = true,
            hasLocationPermission = hasPermission,
            selectedRiderId = selectedRiderId,
            riders = listOf(
                RiderMapItem(
                    riderId = "self",
                    displayName = "You",
                    isSelf = true,
                    isHost = true,
                    isSpeaking = false,
                    isGhost = false,
                    lat = 18.5204,
                    lng = 73.8567,
                    speedMps = 14f,
                    headingDeg = 45f,
                    locationTimestampMs = now,
                    distanceFromSelfMeters = 0.0,
                    isLocationRecent = true
                ),
                RiderMapItem(
                    riderId = "r2",
                    displayName = "Kabir Sharma-Patil",
                    isSelf = false,
                    isHost = false,
                    isSpeaking = true,
                    isGhost = false,
                    lat = 18.5280,
                    lng = 73.8600,
                    speedMps = 16.6f,
                    headingDeg = 90f,
                    locationTimestampMs = now - 3_000L,
                    distanceFromSelfMeters = 910.0,
                    isLocationRecent = true
                ),
                RiderMapItem(
                    riderId = "r3",
                    displayName = "Meera",
                    isSelf = false,
                    isHost = false,
                    isSpeaking = false,
                    isGhost = false,
                    lat = null,
                    lng = null,
                    speedMps = null,
                    headingDeg = null,
                    locationTimestampMs = null,
                    distanceFromSelfMeters = null,
                    isLocationRecent = false
                )
            )
        )
    }

    @Test
    fun entryPill_rendersLiveCountAndHandlesClick() {
        var clicked = 0
        val ctx = englishContext()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                RiderVoiceTheme {
                    ConvoyMapEntryPill(
                        ridersOnMapCount = 2,
                        totalRidersCount = 3,
                        onClick = { clicked++ }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("convoy_map_entry_pill").assertIsDisplayed()
        composeRule.onNodeWithText("Map · 2/3").assertIsDisplayed()
        composeRule.onNodeWithTag("convoy_map_entry_pill").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun liveMapScreen_narrow320dpLargeFont_rendersWithoutOverflowAndTriggersActions() {
        var backClicks = 0
        var styleClicks = 0
        var navigatedRiderId: String? = null
        val ctx = englishContext()

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalConfiguration provides ctx.resources.configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = 1.3f)
            ) {
                RiderVoiceTheme {
                    ConvoyLiveMapScreen(
                        roomName = "Lonavala Dawn Ride",
                        state = sampleState(selectedRiderId = "r2", hasPermission = false),
                        isOfflineMeshActive = false,
                        onBackToVoice = { backClicks++ },
                        onSelectRider = {},
                        onCycleMapStyle = { styleClicks++ },
                        onFitConvoy = {},
                        onCenterSelf = {},
                        onUserCameraGesture = {},
                        onRequestLocationPermission = {},
                        onNavigateToRider = { navigatedRiderId = it.riderId },
                        mapViewportOverride = { state, _, _, mod ->
                            Box(mod.fillMaxSize()) {
                                Text("MapViewport:${state.ridersWithLocation.size}")
                            }
                        }
                    )
                }
            }
        }

        val rootBounds = composeRule.onNodeWithTag("convoy_live_map_screen").getBoundsInRoot()
        val backBounds = composeRule.onNodeWithTag("map_back_to_voice_btn").getBoundsInRoot()
        val fitBounds = composeRule.onNodeWithTag("map_top_fit_all_btn").getBoundsInRoot()
        val navBounds = composeRule.onNodeWithTag("selected_rider_navigate_btn").getBoundsInRoot()

        assertTrue(backBounds.height >= 48.dp)
        assertTrue(fitBounds.height >= 48.dp)
        assertTrue(fitBounds.right <= rootBounds.right + 1.dp)
        assertTrue(navBounds.right <= rootBounds.right + 1.dp)

        composeRule.onNodeWithTag("map_permission_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("selected_rider_detail_card").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_map_chip_self").assertIsDisplayed()
        composeRule.onNodeWithTag("rider_map_chip_r2").assertIsDisplayed()

        composeRule.onNodeWithTag("map_cycle_style_btn").performClick()
        composeRule.onNodeWithTag("selected_rider_navigate_btn").performClick()
        composeRule.onNodeWithTag("map_back_to_voice_btn").performClick()

        assertEquals(1, styleClicks)
        assertEquals("r2", navigatedRiderId)
        assertEquals(1, backClicks)
    }

    @Test
    fun liveMapScreen_displaysHostedRouteBannerAndDirectionsWithoutOverflow() {
        var routeNavClicks = 0
        val ctx = englishContext()
        val stateWithRoute = sampleState(selectedRiderId = null, hasPermission = true).copy(
            route = ConvoyRouteUiState(
                originName = "Pune Railway Station",
                originLatLng = LatLng(18.5289, 73.8744),
                destinationName = "Tiger Point, Lonavala",
                destinationLatLng = LatLng(18.7052, 73.3924),
                distanceMeters = 68_400,
                estimatedDurationMin = 84
            )
        )

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalConfiguration provides ctx.resources.configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = 1.3f)
            ) {
                RiderVoiceTheme {
                    ConvoyLiveMapScreen(
                        roomName = "Lonavala Dawn Ride",
                        state = stateWithRoute,
                        isOfflineMeshActive = false,
                        onBackToVoice = {},
                        onSelectRider = {},
                        onCycleMapStyle = {},
                        onFitConvoy = {},
                        onCenterSelf = {},
                        onUserCameraGesture = {},
                        onRequestLocationPermission = {},
                        onNavigateToRider = {},
                        onNavigateToRouteDestination = { routeNavClicks++ },
                        mapViewportOverride = { state, _, _, mod ->
                            Box(mod.fillMaxSize()) {
                                Text("Route:${state.route?.destinationName}")
                            }
                        }
                    )
                }
            }
        }

        val rootBounds = composeRule.onNodeWithTag("convoy_live_map_screen").getBoundsInRoot()
        val bannerBounds = composeRule.onNodeWithTag("map_hosted_route_banner").getBoundsInRoot()
        val goBtnBounds = composeRule.onNodeWithTag("map_route_directions_btn").getBoundsInRoot()

        assertTrue(bannerBounds.right <= rootBounds.right + 1.dp)
        assertTrue(goBtnBounds.right <= rootBounds.right + 1.dp)
        assertTrue(goBtnBounds.height >= 48.dp)

        composeRule.onNodeWithTag("map_hosted_route_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("map_route_directions_btn").performClick()
        assertEquals(1, routeNavClicks)
    }

    @Test
    fun liveMapScreen_preStartMeetupShowsMeetupAndDestControlsWithoutOverflow() {
        var meetupClicks = 0
        var destClicks = 0
        val ctx = englishContext()
        val stateWithMeetup = sampleState(selectedRiderId = null, hasPermission = true).copy(
            route = ConvoyRouteUiState(
                originName = "Pune Station",
                originLatLng = LatLng(18.5289, 73.8744),
                meetupPoint = "Chandni Chowk",
                meetupLatLng = LatLng(18.5074, 73.7844),
                destinationName = "Tiger Point, Lonavala",
                destinationLatLng = LatLng(18.7052, 73.3924),
                distanceToMeetupMeters = 7_600,
                distanceToDestinationMeters = 68_400,
                estimatedDurationMin = 84
            )
        )

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalConfiguration provides ctx.resources.configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = 1.2f)
            ) {
                RiderVoiceTheme {
                    ConvoyLiveMapScreen(
                        roomName = "Lonavala Dawn Ride",
                        state = stateWithMeetup,
                        isOfflineMeshActive = false,
                        isPreStartLobby = true,
                        onBackToVoice = {},
                        onSelectRider = {},
                        onCycleMapStyle = {},
                        onFitConvoy = {},
                        onCenterSelf = {},
                        onUserCameraGesture = {},
                        onRequestLocationPermission = {},
                        onNavigateToRider = {},
                        onNavigateToRouteDestination = { destClicks++ },
                        onFocusMeetup = { meetupClicks++ },
                        mapViewportOverride = { state, _, _, mod ->
                            Box(mod.fillMaxSize()) {
                                Text("Meetup:${state.route?.meetupPoint}")
                            }
                        }
                    )
                }
            }
        }

        val rootBounds = composeRule.onNodeWithTag("convoy_live_map_screen").getBoundsInRoot()
        val bannerBounds = composeRule.onNodeWithTag("map_hosted_route_banner").getBoundsInRoot()
        val meetupBounds = composeRule.onNodeWithTag("map_route_meetup_btn").getBoundsInRoot()
        val destBounds = composeRule.onNodeWithTag("map_route_directions_btn").getBoundsInRoot()

        assertTrue(bannerBounds.right <= rootBounds.right + 1.dp)
        assertTrue(meetupBounds.height >= 48.dp)
        assertTrue(destBounds.right <= rootBounds.right + 1.dp)

        composeRule.onNodeWithTag("map_route_meetup_btn").performClick()
        composeRule.onNodeWithTag("map_route_directions_btn").performClick()
        assertEquals(1, meetupClicks)
        assertEquals(1, destClicks)
    }
}

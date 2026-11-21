package com.ridervoice.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.network.ApiService
import com.ridervoice.ui.screens.PostRideSummaryScreen
import com.ridervoice.ui.screens.RideReplayScreen
import com.ridervoice.ui.screens.RideStatsScreen
import com.ridervoice.ui.screens.RoutePlannerScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.RideStatsViewModel
import com.ridervoice.ui.viewmodels.RoutePlannerViewModel
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-U10 Real-Data Binding Verification:
 * - Injected values render accurately on PostRideSummaryScreen, RideReplayScreen, and RideStatsScreen.
 * - Absent metrics render honest placeholders ("--") or empty states, never fabricated values.
 * - Error states render visibly (RideReplayScreen InlineError).
 * - RoutePlannerScreen clearly tags offline preview route metrics as "Preview estimate".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class RealDataBindingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRideDao(initial: List<RideSessionEntity>) : RideDao {
        private val flow = MutableStateFlow(initial)
        override suspend fun insertSession(session: RideSessionEntity) {}
        override suspend fun updateSession(session: RideSessionEntity) {}
        override suspend fun insertWaypoint(waypoint: RawWaypointEntity) {}
        override suspend fun insertWaypoints(waypoints: List<RawWaypointEntity>) {}
        override suspend fun insertEvent(event: ConvoyEventEntity) {}
        override suspend fun getSessionById(sessionId: String): RideSessionEntity? = flow.value.firstOrNull { it.id == sessionId }
        override suspend fun getUnsyncedSessions(): List<RideSessionEntity> = emptyList()
        override suspend fun getWaypointsForSession(sessionId: String): List<RawWaypointEntity> = emptyList()
        override suspend fun getEventsForSession(sessionId: String): List<ConvoyEventEntity> = emptyList()
        override fun getAllSessions(): Flow<List<RideSessionEntity>> = flow
        override suspend fun deleteWaypointsForSession(sessionId: String) {}
        override suspend fun getMaxSpeed(sessionId: String): Float? = null
        override suspend fun getAllTimeMaxSpeed(): Float? = 20f
        override suspend fun deleteSession(sessionId: String) {}
    }

    @Test
    fun postRideSummary_rendersInjectedMetrics_andShowsDashForMissingPacketsLost() {
        composeRule.setContent {
            RiderVoiceTheme {
                PostRideSummaryScreen(
                    durationMinutes = 95,
                    distanceKm = 74.5f,
                    topSpeed = 108f,
                    convoyName = "Sahyadri Dawn",
                    sampleCount = 0,
                    lastMetric = null,
                    onSaveToLogbook = {},
                    onDiscard = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sahyadri Dawn").assertIsDisplayed()
        composeRule.onNodeWithText("74.5").assertIsDisplayed()
        composeRule.onNodeWithText("1h 35m").assertIsDisplayed()
        composeRule.onNodeWithText("108").assertIsDisplayed()
        composeRule.onNodeWithText("CELL / WI-FI").assertDoesNotExist()
        // Missing diagnostic metric must show "--" rather than fabricating "0" packets lost
        composeRule.onNodeWithTag("summary_diag_pkts_lost_value")
            .assertIsDisplayed()
            .assertTextEquals("--")
    }

    @Test
    fun rideReplayScreen_rendersInjectedSession_emptyGpsState_andErrorState() {
        val start = 1_700_000_000_000L
        val session = RideSessionEntity(
            id = "s-42",
            roomName = "Tamhini Crest",
            startTime = start,
            endTime = start + 1_800_000L,
            totalDistanceMeters = 42_500f,
            isSynced = true
        )
        composeRule.setContent {
            RiderVoiceTheme {
                RideReplayScreen(
                    rideId = "s-42",
                    session = session,
                    waypoints = emptyList(),
                    events = emptyList(),
                    errorMessage = "Simulated replay read failure",
                    onBack = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tamhini Crest").assertIsDisplayed()
        composeRule.onNodeWithText("Simulated replay read failure").assertIsDisplayed()
        composeRule.onNodeWithText("No GPS track recorded").assertIsDisplayed()
    }

    @Test
    fun rideStatsScreen_rendersEmptyStateWhenDaoEmpty() {
        val vm = RideStatsViewModel(FakeRideDao(emptyList()), mockk<ApiService>(relaxed = true))
        composeRule.setContent {
            RiderVoiceTheme {
                RideStatsScreen(viewModel = vm, onBackClick = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No rides logged yet").assertIsDisplayed()
    }

    @Test
    fun routePlannerScreen_labelsStaticPreviewMetricsHonestly() {
        val vm = RoutePlannerViewModel()
        vm.updateOrigin("Pune")
        vm.updateDestination("Lonavala")

        composeRule.setContent {
            RiderVoiceTheme {
                RoutePlannerScreen(onBackClick = {}, viewModel = vm)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("route_preview_estimate_badge").assertIsDisplayed()
    }
}

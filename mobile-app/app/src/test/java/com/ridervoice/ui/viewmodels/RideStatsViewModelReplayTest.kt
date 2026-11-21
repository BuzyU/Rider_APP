package com.ridervoice.ui.viewmodels

import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.network.ApiService
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * WP-U5 Verification:
 * - RideStatsViewModel totals (distance, time, avgSpeed, topSpeed, totalRidesCount) remain unchanged.
 * - sessions and ReplayData.session/waypoints/events come strictly from RideDao.
 * - DAO failures in loadReplay() populate ReplayData.errorMessage instead of being silently masked.
 * - MainActivity update-complete button uses localized R.string.action_continue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideStatsViewModelReplayTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeRideDao(
        initialSessions: List<RideSessionEntity> = emptyList(),
        private val waypointsBySession: Map<String, List<RawWaypointEntity>> = emptyMap(),
        private val eventsBySession: Map<String, List<ConvoyEventEntity>> = emptyMap(),
        private val allTimeMaxSpeedMps: Float? = null,
        private val failReplayLookup: Boolean = false
    ) : RideDao {
        private val sessionsFlow = MutableStateFlow(initialSessions)

        override suspend fun insertSession(session: RideSessionEntity) {}
        override suspend fun updateSession(session: RideSessionEntity) {}
        override suspend fun insertWaypoint(waypoint: RawWaypointEntity) {}
        override suspend fun insertWaypoints(waypoints: List<RawWaypointEntity>) {}
        override suspend fun insertEvent(event: ConvoyEventEntity) {}

        override suspend fun getSessionById(sessionId: String): RideSessionEntity? {
            if (failReplayLookup) throw IllegalStateException("Simulated DAO read error")
            return sessionsFlow.value.firstOrNull { it.id == sessionId }
        }

        override suspend fun getUnsyncedSessions(): List<RideSessionEntity> = emptyList()

        override suspend fun getWaypointsForSession(sessionId: String): List<RawWaypointEntity> {
            if (failReplayLookup) throw IllegalStateException("Simulated DAO read error")
            return waypointsBySession[sessionId].orEmpty()
        }

        override suspend fun getEventsForSession(sessionId: String): List<ConvoyEventEntity> {
            if (failReplayLookup) throw IllegalStateException("Simulated DAO read error")
            return eventsBySession[sessionId].orEmpty()
        }

        override fun getAllSessions(): Flow<List<RideSessionEntity>> = sessionsFlow
        override suspend fun deleteWaypointsForSession(sessionId: String) {}
        override suspend fun getMaxSpeed(sessionId: String): Float? = null
        override suspend fun getAllTimeMaxSpeed(): Float? = allTimeMaxSpeedMps
        override suspend fun deleteSession(sessionId: String) {}
    }

    @Test
    fun totalsAndSessionsMatchRealDaoData_andLoadReplayReturnsRealSession() = runTest {
        val start = 1_700_000_000_000L
        val session1 = RideSessionEntity(
            id = "ride-101",
            roomName = "Ghat Morning Loop",
            startTime = start,
            endTime = start + 3_600_000L, // 1 hour
            totalDistanceMeters = 60_000f, // 60 km
            isSynced = true
        )
        val wp1 = RawWaypointEntity("wp-1", "ride-101", 18.7481, 73.4069, 20f, 90f, start)
        val wp2 = RawWaypointEntity("wp-2", "ride-101", 18.7581, 73.4169, 25f, 95f, start + 60_000L)
        val ev1 = ConvoyEventEntity("ev-1", "ride-101", "STOP", 18.7581, 73.4169, start + 60_000L)

        val dao = FakeRideDao(
            initialSessions = listOf(session1),
            waypointsBySession = mapOf("ride-101" to listOf(wp1, wp2)),
            eventsBySession = mapOf("ride-101" to listOf(ev1)),
            allTimeMaxSpeedMps = 25f // 90 km/h
        )
        val api = mockk<ApiService>(relaxed = true)

        val vm = RideStatsViewModel(dao, api)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("60.0", state.totalDistance)
        assertEquals("01:00:00", state.totalTime)
        assertEquals("60", state.avgSpeed)
        assertEquals("90", state.topSpeed)
        assertEquals(1, state.totalRidesCount)
        assertEquals(listOf(session1), state.sessions)

        vm.loadReplay("ride-101")
        advanceUntilIdle()

        val replay = vm.replayData.value
        assertNotNull("ReplayData should be populated from DAO", replay)
        assertEquals("ride-101", replay!!.sessionId)
        assertEquals(session1, replay.session)
        assertEquals(2, replay.waypoints.size)
        assertEquals(1, replay.events.size)
        val errField = replay::class.java.declaredFields.firstOrNull { it.name == "errorMessage" }
        assertNotNull("ReplayData must expose errorMessage field so DAO errors are not masked", errField)
        errField!!.isAccessible = true
        assertNull("Successful replay lookup must have null errorMessage", errField.get(replay))
    }

    @Test
    fun loadReplay_whenDaoThrows_surfacesErrorMessageInsteadOfMasking() = runTest {
        val dao = FakeRideDao(failReplayLookup = true)
        val api = mockk<ApiService>(relaxed = true)

        val vm = RideStatsViewModel(dao, api)
        advanceUntilIdle()

        vm.loadReplay("ride-broken")
        advanceUntilIdle()

        val replay = vm.replayData.value
        assertNotNull("ReplayData should be emitted on DAO failure", replay)
        val errField = replay!!::class.java.declaredFields.firstOrNull { it.name == "errorMessage" }
        assertNotNull("ReplayData must expose errorMessage field", errField)
        errField!!.isAccessible = true
        val errValue = errField.get(replay) as? String
        assertTrue(
            "DAO failure must populate non-blank errorMessage instead of silently masking as empty",
            !errValue.isNullOrBlank()
        )
    }

    @Test
    fun mainActivityUpdateContinueButton_usesLocalizedStringResource() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice/MainActivity.kt"),
            File("app/src/main/java/com/ridervoice/MainActivity.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/MainActivity.kt")
        )
        val mainActivity = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate MainActivity.kt")
        val text = mainActivity.readText()
        assertTrue(
            "MainActivity update-complete button must use localized R.string.action_continue",
            text.contains("R.string.action_continue")
        )
    }
}

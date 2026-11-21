package com.ridervoice.services

import android.location.Location
import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.viewmodels.PostRideSummaryViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideRecorderTitleTest {

    private class InMemoryRideDao : RideDao {
        val sessions = LinkedHashMap<String, RideSessionEntity>()
        private val flow = MutableStateFlow<List<RideSessionEntity>>(emptyList())

        override suspend fun insertSession(session: RideSessionEntity) {
            sessions[session.id] = session
            flow.value = sessions.values.toList()
        }

        override suspend fun updateSession(session: RideSessionEntity) {
            sessions[session.id] = session
            flow.value = sessions.values.toList()
        }

        override suspend fun insertWaypoint(waypoint: RawWaypointEntity) {}
        override suspend fun insertWaypoints(waypoints: List<RawWaypointEntity>) {}
        override suspend fun insertEvent(event: ConvoyEventEntity) {}
        override suspend fun getSessionById(sessionId: String): RideSessionEntity? = sessions[sessionId]
        override suspend fun getUnsyncedSessions(): List<RideSessionEntity> = sessions.values.filter { !it.isSynced }
        override suspend fun getWaypointsForSession(sessionId: String): List<RawWaypointEntity> = emptyList()
        override suspend fun getEventsForSession(sessionId: String): List<ConvoyEventEntity> = emptyList()
        override fun getAllSessions(): Flow<List<RideSessionEntity>> = flow
        override suspend fun deleteWaypointsForSession(sessionId: String) {}
        override suspend fun getMaxSpeed(sessionId: String): Float? = null
        override suspend fun getAllTimeMaxSpeed(): Float? = null
        override suspend fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)
            flow.value = sessions.values.toList()
        }
    }

    private val testRoomUuid = "9fb15f96-ba3e-4e30-ac23-5a1234567890"

    @Test
    fun startRecording_withRoomUuid_savesHumanReadableRoomNameNotUuid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dao = InMemoryRideDao()
        val locSvc = mockk<LocationService>(relaxed = true)
        every { locSvc.currentLocation } returns MutableStateFlow<Location?>(null)

        val repo = ActiveRideRepository(SecurePreferences(null), mockk<ApiService>(relaxed = true))
        repo.enterLobby(roomId = testRoomUuid, name = "Lonavala Sunday", isHost = true)
        repo.goLive()

        val recorder = RideRecorder(dao, locSvc, dispatcher, repo)
        // VoiceForegroundService passes ride.roomId (UUID)
        recorder.startRecording(testRoomUuid)
        advanceUntilIdle()

        assertEquals(1, dao.sessions.size)
        val stored = dao.sessions.values.single()
        assertEquals("Lonavala Sunday", stored.roomName)

        // Transport flap / state re-emission with same UUID keeps the single session
        recorder.startRecording(testRoomUuid)
        advanceUntilIdle()
        assertEquals(1, dao.sessions.size)

        // Ending the ride keeps the room name and exposes it in RideSummary
        repo.clear(ActiveRideRepository.ClearReason.LEFT)
        recorder.stopRecording()
        advanceUntilIdle()

        val summary = recorder.lastSummary.value
        assertNotNull(summary)
        assertEquals("Lonavala Sunday", summary?.roomName)
        assertEquals("Lonavala Sunday", dao.getSessionById(summary!!.sessionId)?.roomName)
    }

    @Test
    fun postRideSummarySave_blankTitleDefaultsToRoomName_andCustomTitleOverrides() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dao = InMemoryRideDao()
        val locSvc = mockk<LocationService>(relaxed = true)
        every { locSvc.currentLocation } returns MutableStateFlow<Location?>(null)
        val diag = mockk<NetworkDiagnosticsCollector>(relaxed = true)
        every { diag.latestSelfMetric } returns MutableStateFlow(null)
        every { diag.sampleCount } returns MutableStateFlow(0)

        val repo = ActiveRideRepository(SecurePreferences(null), mockk<ApiService>(relaxed = true))

        // Case 1: Blank ride title -> defaults to room name (even if recorder initially had UUID)
        repo.enterLobby(roomId = testRoomUuid, name = "Tamhini Loop", isHost = true)
        repo.goLive()
        val recorder = RideRecorder(dao, locSvc, dispatcher, repo)
        recorder.startRecording(testRoomUuid)
        advanceUntilIdle()
        repo.clear(ActiveRideRepository.ClearReason.LEFT)
        recorder.stopRecording()
        advanceUntilIdle()

        val sid1 = recorder.lastSummary.value!!.sessionId
        val vm1 = PostRideSummaryViewModel(recorder, diag, repo)
        assertEquals("Tamhini Loop", vm1.convoyName)
        vm1.save(rideTitle = "   ", privacy = "SQUAD")
        advanceUntilIdle()

        assertNull("Summary must clear after saving", recorder.lastSummary.value)
        assertEquals("Tamhini Loop", dao.getSessionById(sid1)?.roomName)

        // Case 2: Custom ride title provided -> overrides room name
        val uuid2 = "11111111-2222-3333-4444-555555555555"
        repo.enterLobby(roomId = uuid2, name = "Tamhini Loop", isHost = true)
        repo.goLive()
        recorder.startRecording(uuid2)
        advanceUntilIdle()
        repo.clear(ActiveRideRepository.ClearReason.LEFT)
        recorder.stopRecording()
        advanceUntilIdle()

        val sid2 = recorder.lastSummary.value!!.sessionId
        val vm2 = PostRideSummaryViewModel(recorder, diag, repo)
        vm2.save(rideTitle = "Monsoon Dawn Ride", privacy = "PRIVATE")
        advanceUntilIdle()

        assertNull("Summary must clear after saving", recorder.lastSummary.value)
        assertEquals("Monsoon Dawn Ride", dao.getSessionById(sid2)?.roomName)
    }
}

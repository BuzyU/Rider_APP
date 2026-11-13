package com.ridervoice.ui.viewmodels

import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SosViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private open class FakeApiService(
        var alertResponse: Response<SosAlertResponse>
    ) : ApiService {
        override suspend fun updateFcmToken(body: FcmTokenRequest): Response<Any> = Response.success(Unit)
        override suspend fun upsertProfile(body: ProfileRequest): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getMyProfile(): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun searchByHandle(handle: String): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getFriendsList(userId: String): Response<List<Friend>> = throw NotImplementedError()
        override suspend fun sendFriendRequest(body: FriendRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getPendingInvites(userId: String): Response<List<RideInvite>> = throw NotImplementedError()
        override suspend fun respondToInvite(body: InviteRespondRequest): Response<Any> = throw NotImplementedError()
        override suspend fun sendRideInvite(body: SendInviteRequest): Response<Any> = throw NotImplementedError()
        override suspend fun createConvoy(body: ConvoyCreateRequest): Response<ConvoyCreateResponse> = throw NotImplementedError()
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> = throw NotImplementedError()
        override suspend fun startRide(roomName: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getJoinToken(body: JoinTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun generateShareLink(roomName: String): Response<ShareLinkResponse> = throw NotImplementedError()
        override suspend fun joinViaToken(body: JoinViaTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun removeRiderFromConvoy(roomName: String, userId: String): Response<Any> = throw NotImplementedError()
        override suspend fun endRideForEveryone(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun transferHost(roomName: String, body: TransferHostRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getRoomToken(body: RoomTokenRequest): Response<RoomData> = throw NotImplementedError()

        override suspend fun sendSosAlert(body: SosAlertRequest): Response<SosAlertResponse> = alertResponse

        override suspend fun cancelEmergencyAlert(body: CancelAlertRequest): Response<CancelAlertResponse> =
            Response.success(CancelAlertResponse(cancelled = true))

        override suspend fun syncRide(body: SyncRideRequest): Response<SyncRideResponse> = throw NotImplementedError()
        override suspend fun getRideHistory(): Response<List<RideHistoryResponse>> = throw NotImplementedError()
        override suspend fun finalizeDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getLatestDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun leaveLobby(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = throw NotImplementedError()
    }

    @Test
    fun `sendAlert transitions to NotSent when response sent is false`() = runTest(testDispatcher) {
        val fakeApi = FakeApiService(
            alertResponse = Response.success(
                SosAlertResponse(
                    sent = false,
                    reason = "NO_ACTIVE_RIDE",
                    emergencyNumber = "112",
                    emergencyName = "National Emergency Number",
                    message = "SOS not dispatched: no active convoy found."
                )
            )
        )

        val vm = SosViewModel(fakeApi) { Pair(18.5204, 73.8567) }
        vm.sendAlert("GLOBAL")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue("State must be NotSent, was $state", state is SosState.NotSent)
        val notSent = state as SosState.NotSent
        assertEquals("NO_ACTIVE_RIDE", notSent.reason)
        assertEquals("112", notSent.emergencyNumber)
        assertEquals("SOS NOT SENT. Call 112 now.", notSent.message)
    }

    @Test
    fun `sendAlert transitions to Sent with recipientsCount when response sent is true`() = runTest(testDispatcher) {
        val fakeApi = FakeApiService(
            alertResponse = Response.success(
                SosAlertResponse(
                    sent = true,
                    alertId = "alert-abc-123",
                    recipientsCount = 3
                )
            )
        )

        val vm = SosViewModel(fakeApi) { Pair(18.5204, 73.8567) }
        vm.sendAlert("Weekend-Ride")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue("State must be Sent, was $state", state is SosState.Sent)
        val sent = state as SosState.Sent
        assertEquals("alert-abc-123", sent.alertId)
        assertEquals(3, sent.recipientsCount)
    }

    @Test
    fun `sendAlert transitions to NotSent when server returns error response`() = runTest(testDispatcher) {
        val errorResponse = Response.error<SosAlertResponse>(
            500,
            "Internal Server Error".toResponseBody("text/plain".toMediaType())
        )
        val fakeApi = FakeApiService(alertResponse = errorResponse)

        val outbox = com.ridervoice.services.InMemorySosOutbox()
        val vm = SosViewModel(fakeApi, { Pair(18.5204, 73.8567) }, outbox)
        vm.sendAlert("Broken-Ride")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue("State must be NotSent on server error, was $state", state is SosState.NotSent)
        assertEquals("QUEUED_OFFLINE", (state as SosState.NotSent).reason)
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `sendAlert drops from outbox on 403 Forbidden`() = runTest(testDispatcher) {
        val errorResponse = Response.error<SosAlertResponse>(
            403,
            "Forbidden".toResponseBody("text/plain".toMediaType())
        )
        val fakeApi = FakeApiService(alertResponse = errorResponse)

        val outbox = com.ridervoice.services.InMemorySosOutbox()
        val vm = SosViewModel(fakeApi, { Pair(18.5204, 73.8567) }, outbox)
        vm.sendAlert("Forbidden-Ride")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue("State must be NotSent on 403, was $state", state is SosState.NotSent)
        assertEquals("SERVER_ERROR", (state as SosState.NotSent).reason)
        assertEquals(0, outbox.pending().size)
    }

    @Test
    fun `cancelAlert removes pending alert from outbox`() = runTest(testDispatcher) {
        val errorResponse = Response.error<SosAlertResponse>(
            500,
            "Error".toResponseBody("text/plain".toMediaType())
        )
        val fakeApi = FakeApiService(alertResponse = errorResponse)
        val outbox = com.ridervoice.services.InMemorySosOutbox()
        val vm = SosViewModel(fakeApi, { Pair(18.5204, 73.8567) }, outbox)
        vm.sendAlert("Pending-Ride")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, outbox.pending().size)

        vm.cancelAlert("Pending-Ride", "FALSE_ALARM")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, outbox.pending().size)
        assertTrue(vm.state.value is SosState.Idle)
    }

    @Test
    fun `sendAlert queues alert offline and flushes upon reconnect`() = runTest(testDispatcher) {
        var callCount = 0
        val fakeApi = object : FakeApiService(Response.success(SosAlertResponse(sent = false))) {
            override suspend fun sendSosAlert(body: SosAlertRequest): Response<SosAlertResponse> {
                callCount++
                if (callCount == 1) {
                    throw java.io.IOException("No internet connection")
                }
                return Response.success(SosAlertResponse(sent = true, alertId = "queued-alert-1", recipientsCount = 2))
            }
        }

        val vm = SosViewModel(fakeApi) { Pair(18.5204, 73.8567) }
        vm.sendAlert("Offline-Ride")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue("State must be NotSent (queued offline), was $state", state is SosState.NotSent)
        assertEquals("QUEUED_OFFLINE", (state as SosState.NotSent).reason)

        // When network returns, flush pending alerts
        vm.flushPendingAlerts()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, callCount)
    }
}

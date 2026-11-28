package com.ridervoice.ui.screens.map

import android.content.Context
import android.location.Location
import com.ridervoice.models.ConvoyRoute
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.services.LocationService
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvoyMapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var participantsFlow: MutableStateFlow<List<Participant>>
    private lateinit var remoteLocationsFlow: MutableStateFlow<Map<String, RiderLocation>>
    private lateinit var activeSpeakerFlow: MutableStateFlow<String?>
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionState>
    private lateinit var currentLocationFlow: MutableStateFlow<Location?>
    private lateinit var rideStateFlow: MutableStateFlow<ActiveRideRepository.ActiveRide?>

    private lateinit var liveKitManager: LiveKitManager
    private lateinit var locationService: LocationService
    private lateinit var activeRideRepository: ActiveRideRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        participantsFlow = MutableStateFlow(emptyList())
        remoteLocationsFlow = MutableStateFlow(emptyMap())
        activeSpeakerFlow = MutableStateFlow(null)
        connectionStateFlow = MutableStateFlow(ConnectionState.CONNECTED)
        currentLocationFlow = MutableStateFlow(null)
        rideStateFlow = MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = "ride-101",
                displayName = "Sahyadri Breakfast Ride",
                isHost = true,
                phase = ActiveRideRepository.Phase.LIVE
            )
        )

        liveKitManager = mockk(relaxed = true) {
            every { participants } returns participantsFlow
            every { remoteLocations } returns remoteLocationsFlow
            every { activeSpeaker } returns activeSpeakerFlow
            every { connectionState } returns connectionStateFlow
            every { convoyRoute } returns MutableStateFlow(null)
        }
        locationService = mockk(relaxed = true) {
            every { currentLocation } returns currentLocationFlow
        }
        activeRideRepository = mockk(relaxed = true) {
            every { state } returns rideStateFlow
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        selfId: String = "uid-self",
        nowMs: Long = 100_000L,
        hasPermission: Boolean = true
    ): ConvoyMapViewModel {
        val mockAppContext = mockk<Context>(relaxed = true)
        return ConvoyMapViewModel(
            appContext = mockAppContext,
            liveKitManager = liveKitManager,
            locationService = locationService,
            activeRideRepository = activeRideRepository
        ).apply {
            currentUserIdProvider = { selfId }
            nowMsProvider = { nowMs }
            locationPermissionChecker = { hasPermission }
            refreshTrackingIfPermitted()
        }
    }

    private fun mockLocation(lat: Double, lng: Double, speed: Float = 15f, bearing: Float = 90f): Location {
        return mockk(relaxed = true) {
            every { latitude } returns lat
            every { longitude } returns lng
            every { getSpeed() } returns speed
            every { getBearing() } returns bearing
        }
    }

    @Test
    fun `combines self and all voice room riders with live locations distances and speakers`() = runTest {
        val now = 200_000L
        val vm = createViewModel(selfId = "uid-self", nowMs = now)
        val collectJob = launch { vm.uiState.collect {} }

        currentLocationFlow.value = mockLocation(lat = 18.5204, lng = 73.8567, speed = 12.5f)
        participantsFlow.value = listOf(
            Participant(identity = "uid-self", displayName = "Aarav"),
            Participant(identity = "uid-2", displayName = "Kabir"),
            Participant(identity = "uid-3", displayName = "Meera", isGhost = true)
        )
        remoteLocationsFlow.value = mapOf(
            "uid-2" to RiderLocation(
                riderId = "uid-2",
                lat = 18.5294,
                lng = 73.8567,
                speed = 20.0f,
                heading = 180f,
                timestamp = now - 5_000L
            )
        )
        activeSpeakerFlow.value = "uid-2"

        advanceUntilIdle()
        val state = vm.uiState.value

        assertEquals("Sahyadri Breakfast Ride", state.rideName)
        assertTrue(state.isOnlineVoiceConnected)
        assertEquals(3, state.riders.size)
        assertEquals(2, state.ridersWithLocation.size)

        val self = state.riders[0]
        assertTrue(self.isSelf)
        assertTrue(self.isHost)
        assertEquals(18.5204, self.lat!!, 0.0001)
        assertEquals(45, self.speedKmh)

        val kabir = state.riders[1]
        assertEquals("uid-2", kabir.riderId)
        assertEquals("Kabir", kabir.displayName)
        assertTrue(kabir.isSpeaking)
        assertTrue(kabir.isLocationRecent)
        assertEquals(72, kabir.speedKmh)
        assertNotNull(kabir.distanceFromSelfMeters)
        // ~1km north of self
        assertTrue(kabir.distanceFromSelfMeters!! in 950.0..1050.0)

        val meera = state.riders[2]
        assertEquals("uid-3", meera.riderId)
        assertTrue(meera.isGhost)
        assertFalse(meera.hasLocation)

        collectJob.cancel()
    }

    @Test
    fun `selectRider toggles selection and disables auto follow`() = runTest {
        val vm = createViewModel()
        val collectJob = launch { vm.uiState.collect {} }

        participantsFlow.value = listOf(Participant(identity = "uid-2", displayName = "Kabir"))
        vm.requestCenterOnSelf()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFollowingSelf)

        vm.selectRider("uid-2")
        advanceUntilIdle()
        assertEquals("uid-2", vm.uiState.value.selectedRiderId)
        assertFalse(vm.uiState.value.isFollowingSelf)

        // Tapping same rider again clears selection card
        vm.selectRider("uid-2")
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedRiderId)

        collectJob.cancel()
    }

    @Test
    fun `cycleMapStyle and camera signals update state cleanly`() = runTest {
        val vm = createViewModel()
        val collectJob = launch { vm.uiState.collect {} }

        advanceUntilIdle()
        assertEquals(ConvoyMapStyle.STANDARD, vm.uiState.value.mapStyle)

        vm.cycleMapStyle()
        advanceUntilIdle()
        assertEquals(ConvoyMapStyle.TERRAIN, vm.uiState.value.mapStyle)

        vm.cycleMapStyle()
        advanceUntilIdle()
        assertEquals(ConvoyMapStyle.SATELLITE, vm.uiState.value.mapStyle)

        vm.cycleMapStyle()
        advanceUntilIdle()
        assertEquals(ConvoyMapStyle.STANDARD, vm.uiState.value.mapStyle)

        vm.requestFitConvoy()
        vm.requestCenterOnSelf()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.fitConvoySignal)
        assertEquals(1, vm.uiState.value.centerSelfSignal)
        assertTrue(vm.uiState.value.isFollowingSelf)

        vm.disableAutoFollow()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFollowingSelf)

        collectJob.cancel()
    }

    @Test
    fun `refreshTrackingIfPermitted starts tracking when location permission granted`() {
        val vm = createViewModel()
        vm.refreshTrackingIfPermitted()
        verify { locationService.startTracking() }
    }

    @Test
    fun `buildBoundsForRiders returns null for fewer than two GPS riders and bounds for two plus`() {
        val single = listOf(
            RiderMapItem(
                riderId = "r1",
                displayName = "You",
                isSelf = true,
                isHost = true,
                isSpeaking = false,
                isGhost = false,
                lat = 18.5204,
                lng = 73.8567,
                speedMps = 10f,
                headingDeg = 0f,
                locationTimestampMs = 1L,
                distanceFromSelfMeters = 0.0,
                isLocationRecent = true
            )
        )
        assertNull(ConvoyMapViewModel.buildBoundsForRiders(single))

        val pair = single + single.first().copy(
            riderId = "r2",
            isSelf = false,
            lat = 19.0760,
            lng = 72.8777
        )
        val bounds = ConvoyMapViewModel.buildBoundsForRiders(pair)
        assertNotNull(bounds)
        assertEquals(18.5204, bounds!!.southwest.latitude, 0.001)
        assertEquals(19.0760, bounds.northeast.latitude, 0.001)
    }

    @Test
    fun `hosted route propagates to uiState and self blip color differs from all friend blips`() = runTest {
        val route = ConvoyRoute(
            originName = "Pune Station",
            originLat = 18.5289,
            originLng = 73.8744,
            destinationName = "Tiger Point, Lonavala",
            destinationLat = 18.7052,
            destinationLng = 73.3924,
            distanceMeters = 68_500,
            estimatedDurationMin = 85,
            encodedPolyline = "_p~iF~ps|U_ulLnnqC"
        )
        rideStateFlow.value = rideStateFlow.value?.copy(route = route)

        val vm = createViewModel(selfId = "uid-self")
        val collectJob = launch { vm.uiState.collect {} }

        participantsFlow.value = listOf(
            Participant(identity = "uid-self", displayName = "Me"),
            Participant(identity = "uid-peer-a", displayName = "Kabir"),
            Participant(identity = "uid-peer-b", displayName = "Meera")
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.route)
        assertEquals("Pune Station", state.route?.originName)
        assertEquals("Tiger Point, Lonavala", state.route?.destinationName)
        assertEquals(2, state.route?.polylinePoints?.size)
        assertTrue(state.hasAnyMapCoordinates)

        val selfBlip = ConvoyMapViewModel.riderBlipColorArgb(state.riders.first { it.isSelf })
        assertEquals(ConvoyMapViewModel.SELF_BLIP_COLOR_ARGB, selfBlip)
        state.riders.filter { !it.isSelf }.forEach { friend ->
            val friendColor = ConvoyMapViewModel.riderBlipColorArgb(friend)
            assertTrue("Friend blip color must differ from self royal-blue blip", friendColor != selfBlip)
        }

        val routeBounds = ConvoyMapViewModel.buildBoundsForRiders(emptyList(), state.route)
        assertNotNull(routeBounds)

        collectJob.cancel()
    }
}

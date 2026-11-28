package com.ridervoice.ui.screens.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.firebase.auth.FirebaseAuth
import com.google.maps.android.compose.MapType
import com.ridervoice.models.ConvoyRoute
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.navigation.GooglePlacesRouteService
import com.ridervoice.network.ApiService
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.services.LocationService
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.components.ParticipantNameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ConvoyMapStyle(val mapType: MapType) {
    STANDARD(MapType.NORMAL),
    TERRAIN(MapType.TERRAIN),
    SATELLITE(MapType.HYBRID);

    fun next(): ConvoyMapStyle = when (this) {
        STANDARD -> TERRAIN
        TERRAIN -> SATELLITE
        SATELLITE -> STANDARD
    }
}

data class RiderMapItem(
    val riderId: String,
    val displayName: String,
    val isSelf: Boolean,
    val isHost: Boolean,
    val isSpeaking: Boolean,
    val isGhost: Boolean,
    val lat: Double?,
    val lng: Double?,
    val speedMps: Float?,
    val headingDeg: Float?,
    val locationTimestampMs: Long?,
    val distanceFromSelfMeters: Double?,
    val isLocationRecent: Boolean
) {
    val hasLocation: Boolean
        get() = lat != null && lng != null && !lat.isNaN() && !lng.isNaN()

    val latLng: LatLng?
        get() = if (hasLocation) LatLng(lat!!, lng!!) else null

    val speedKmh: Int
        get() = ((speedMps ?: 0f).coerceAtLeast(0f) * 3.6f).toInt()
}

data class ConvoyRouteUiState(
    val originName: String? = null,
    val originLatLng: LatLng? = null,
    val destinationName: String? = null,
    val destinationLatLng: LatLng? = null,
    val meetupPoint: String? = null,
    val meetupLatLng: LatLng? = null,
    val polylinePoints: List<LatLng> = emptyList(),
    val distanceMeters: Int? = null,
    val distanceToDestinationMeters: Int? = null,
    val distanceToMeetupMeters: Int? = null,
    val estimatedDurationMin: Int? = null,
    val nextInstruction: String? = null
) {
    val hasAnyRouteInfo: Boolean
        get() = !originName.isNullOrBlank() ||
            !destinationName.isNullOrBlank() ||
            !meetupPoint.isNullOrBlank() ||
            originLatLng != null ||
            destinationLatLng != null ||
            meetupLatLng != null
}

data class ConvoyMapUiState(
    val rideName: String = "",
    val isOnlineVoiceConnected: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val riders: List<RiderMapItem> = emptyList(),
    val selectedRiderId: String? = null,
    val navigateTargetRiderId: String? = null,
    val mapStyle: ConvoyMapStyle = ConvoyMapStyle.STANDARD,
    val isFollowingSelf: Boolean = false,
    val fitConvoySignal: Int = 0,
    val centerSelfSignal: Int = 0,
    val focusDestinationSignal: Int = 0,
    val focusMeetupSignal: Int = 0,
    val route: ConvoyRouteUiState? = null
) {
    val ridersWithLocation: List<RiderMapItem>
        get() = riders.filter { it.hasLocation }

    val selectedRider: RiderMapItem?
        get() = riders.firstOrNull { it.riderId == selectedRiderId }

    val navigateTargetRider: RiderMapItem?
        get() = riders.firstOrNull { it.riderId == navigateTargetRiderId }

    val selfRider: RiderMapItem?
        get() = riders.firstOrNull { it.isSelf }

    val hasAnyMapCoordinates: Boolean
        get() = ridersWithLocation.isNotEmpty() ||
            route?.originLatLng != null ||
            route?.destinationLatLng != null ||
            route?.meetupLatLng != null
}

private data class MapControlState(
    val selectedRiderId: String? = null,
    val navigateTargetRiderId: String? = null,
    val mapStyle: ConvoyMapStyle = ConvoyMapStyle.STANDARD,
    val isFollowingSelf: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val fitConvoySignal: Int = 0,
    val centerSelfSignal: Int = 0,
    val focusDestinationSignal: Int = 0,
    val focusMeetupSignal: Int = 0
)

private data class LiveFeedSnapshot(
    val participants: List<Participant>,
    val remoteLocations: Map<String, RiderLocation>,
    val selfLocation: Location?,
    val activeSpeaker: String?,
    val connectionState: ConnectionState,
    val ride: ActiveRideRepository.ActiveRide?,
    val liveKitRoute: ConvoyRoute?,
    val resolvedRoute: ConvoyRoute?
)

@HiltViewModel
class ConvoyMapViewModel private constructor(
    @ApplicationContext private val appContext: Context,
    private val liveKitManager: LiveKitManager,
    private val locationService: LocationService,
    private val activeRideRepository: ActiveRideRepository,
    private val apiService: ApiService?,
    private val routePlacesService: GooglePlacesRouteService?,
    @Suppress("UNUSED_PARAMETER") privateCtorMarker: Boolean
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        liveKitManager: LiveKitManager,
        locationService: LocationService,
        activeRideRepository: ActiveRideRepository,
        apiService: ApiService,
        routePlacesService: GooglePlacesRouteService
    ) : this(
        appContext = appContext,
        liveKitManager = liveKitManager,
        locationService = locationService,
        activeRideRepository = activeRideRepository,
        apiService = apiService,
        routePlacesService = routePlacesService,
        privateCtorMarker = false
    )

    constructor(
        appContext: Context,
        liveKitManager: LiveKitManager,
        locationService: LocationService,
        activeRideRepository: ActiveRideRepository
    ) : this(
        appContext = appContext,
        liveKitManager = liveKitManager,
        locationService = locationService,
        activeRideRepository = activeRideRepository,
        apiService = null,
        routePlacesService = null,
        privateCtorMarker = false
    )

    @VisibleForTesting
    internal var currentUserIdProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }

    @VisibleForTesting
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var locationPermissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val controls = MutableStateFlow(MapControlState())
    private val resolvedRouteFlow = MutableStateFlow<ConvoyRoute?>(null)
    private val resolvedNextInstructionFlow = MutableStateFlow<String?>(null)
    private var lastRouteRoomRef: String? = null
    private var lastRouteEnrichKey: String? = null

    private val routeFeed = combine(
        activeRideRepository.state,
        liveKitManager.convoyRoute,
        resolvedRouteFlow
    ) { ride, lkRoute, enriched ->
        Triple(ride, lkRoute, enriched)
    }

    private val liveFeed = combine(
        combine(
            liveKitManager.participants,
            liveKitManager.remoteLocations,
            locationService.currentLocation
        ) { participants, remoteLocations, selfLocation ->
            Triple(participants, remoteLocations, selfLocation)
        },
        combine(
            liveKitManager.activeSpeaker,
            liveKitManager.connectionState,
            routeFeed
        ) { activeSpeaker, connectionState, (ride, lkRoute, enriched) ->
            LiveFeedSnapshot(
                participants = emptyList(),
                remoteLocations = emptyMap(),
                selfLocation = null,
                activeSpeaker = activeSpeaker,
                connectionState = connectionState,
                ride = ride,
                liveKitRoute = lkRoute,
                resolvedRoute = enriched
            )
        }
    ) { (participants, remoteLocations, selfLocation), partial ->
        partial.copy(
            participants = participants,
            remoteLocations = remoteLocations,
            selfLocation = selfLocation
        )
    }

    val uiState: StateFlow<ConvoyMapUiState> = combine(liveFeed, controls, resolvedNextInstructionFlow) { feed, control, nextInstr ->
        val mergedRoute = listOfNotNull(
            feed.ride?.route,
            feed.liveKitRoute,
            feed.resolvedRoute
        ).reduceOrNull { acc, next -> acc.mergeWith(next) }

        buildConvoyMapUiState(
            participants = feed.participants,
            remoteLocations = feed.remoteLocations,
            selfLocation = feed.selfLocation,
            activeSpeaker = feed.activeSpeaker,
            connectionState = feed.connectionState,
            ride = feed.ride,
            selfId = currentUserIdProvider(),
            nowMs = nowMsProvider(),
            selectedRiderId = control.selectedRiderId,
            navigateTargetRiderId = control.navigateTargetRiderId,
            mapStyle = control.mapStyle,
            isFollowingSelf = control.isFollowingSelf,
            hasLocationPermission = control.hasLocationPermission,
            fitConvoySignal = control.fitConvoySignal,
            centerSelfSignal = control.centerSelfSignal,
            focusDestinationSignal = control.focusDestinationSignal,
            focusMeetupSignal = control.focusMeetupSignal,
            route = mergedRoute,
            nextInstruction = nextInstr
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ConvoyMapUiState(hasLocationPermission = safeHasLocationPermission())
    )

    fun refreshRoute(roomNameFallback: String) {
        val ride = activeRideRepository.state.value
        val existingRoute = listOfNotNull(
            ride?.route,
            liveKitManager.convoyRoute.value,
            resolvedRouteFlow.value
        ).reduceOrNull { acc, next -> acc.mergeWith(next) }

        val roomRef = ride?.roomId?.takeIf { it.isNotBlank() } ?: roomNameFallback
        if (lastRouteRoomRef != roomRef) {
            lastRouteRoomRef = roomRef
            lastRouteEnrichKey = null
            resolvedRouteFlow.value = null
        }
        val enrichKey = listOfNotNull(
            roomRef,
            existingRoute?.originName,
            existingRoute?.destinationName,
            existingRoute?.originLat?.toString(),
            existingRoute?.destinationLat?.toString(),
            existingRoute?.encodedPolyline
        ).joinToString("|")
        if (lastRouteEnrichKey == enrichKey && existingRoute?.encodedPolyline != null) return
        lastRouteEnrichKey = enrichKey

        viewModelScope.launch {
            var workingRoute = existingRoute

            // Hydrate origin/destination from DB via lobby status if joined without lobby hydration
            if ((workingRoute == null || (workingRoute.originName.isNullOrBlank() && workingRoute.destinationName.isNullOrBlank())) &&
                apiService != null && roomRef.isNotBlank()
            ) {
                try {
                    val res = apiService.getLobbyStatus(roomRef)
                    val body = res.body()
                    if (res.isSuccessful && body != null) {
                        val dbRoute = ConvoyRoute(
                            originName = body.origin,
                            destinationName = body.destination,
                            meetupPoint = body.meetupPoint,
                            estimatedDurationMin = body.estimatedDurationMin
                        ).takeIf { it.hasAnyRouteInfo }
                        if (dbRoute != null) {
                            workingRoute = workingRoute?.mergeWith(dbRoute) ?: dbRoute
                            activeRideRepository.updateRoute(workingRoute)
                        }
                    }
                } catch (_: Exception) {}
            }

            if (workingRoute == null || !workingRoute.hasAnyRouteInfo) return@launch

            val places = routePlacesService
            if (places != null) {
                val needOriginCoords = !workingRoute.originName.isNullOrBlank() && !workingRoute.hasOriginCoords
                val needDestCoords = !workingRoute.destinationName.isNullOrBlank() && !workingRoute.hasDestinationCoords
                val needMeetupCoords = !workingRoute.meetupPoint.isNullOrBlank() && !workingRoute.hasMeetupCoords
                if (needOriginCoords || needDestCoords || needMeetupCoords) {
                    val startDeferred = async {
                        if (needOriginCoords) places.geocodeName(workingRoute!!.originName!!) else null
                    }
                    val destDeferred = async {
                        if (needDestCoords) places.geocodeName(workingRoute!!.destinationName!!) else null
                    }
                    val meetupDeferred = async {
                        if (needMeetupCoords) places.geocodeName(workingRoute!!.meetupPoint!!) else null
                    }
                    val startResolved = startDeferred.await()
                    val destResolved = destDeferred.await()
                    val meetupResolved = meetupDeferred.await()
                    workingRoute = workingRoute.mergeWith(
                        ConvoyRoute(
                            originName = workingRoute.originName ?: startResolved?.name,
                            originLat = startResolved?.lat,
                            originLng = startResolved?.lng,
                            destinationName = workingRoute.destinationName ?: destResolved?.name,
                            destinationLat = destResolved?.lat,
                            destinationLng = destResolved?.lng,
                            meetupPoint = workingRoute.meetupPoint ?: meetupResolved?.name,
                            meetupLat = meetupResolved?.lat,
                            meetupLng = meetupResolved?.lng
                        )
                    )
                }

                val selfLoc = locationService.currentLocation.value
                val legStartLat = workingRoute.originLat ?: workingRoute.meetupLat ?: selfLoc?.latitude
                val legStartLng = workingRoute.originLng ?: workingRoute.meetupLng ?: selfLoc?.longitude
                val legEndLat = workingRoute.destinationLat ?: workingRoute.meetupLat
                val legEndLng = workingRoute.destinationLng ?: workingRoute.meetupLng

                if (legStartLat != null && legStartLng != null && legEndLat != null && legEndLng != null &&
                    (workingRoute.encodedPolyline.isNullOrBlank() || resolvedNextInstructionFlow.value == null)
                ) {
                    val directions = places.fetchRoute(
                        originLat = legStartLat,
                        originLng = legStartLng,
                        destLat = legEndLat,
                        destLng = legEndLng,
                        waypointLat = workingRoute.meetupLat,
                        waypointLng = workingRoute.meetupLng
                    )
                    if (directions != null) {
                        resolvedNextInstructionFlow.value = directions.nextInstruction
                        workingRoute = workingRoute.mergeWith(
                            ConvoyRoute(
                                estimatedDurationMin = workingRoute.estimatedDurationMin ?: directions.durationMinutes,
                                distanceMeters = directions.distanceMeters,
                                encodedPolyline = directions.encodedPolyline
                            )
                        )
                    }
                }
            }

            resolvedRouteFlow.value = workingRoute
            activeRideRepository.updateRoute(workingRoute)
        }
    }

    fun refreshTrackingIfPermitted() {
        val granted = safeHasLocationPermission()
        controls.update { it.copy(hasLocationPermission = granted) }
        if (granted) {
            locationService.startTracking()
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        val effective = granted || safeHasLocationPermission()
        controls.update { it.copy(hasLocationPermission = effective) }
        if (effective) {
            locationService.startTracking()
        }
    }

    fun selectRider(riderId: String?) {
        controls.update {
            val toggled = if (riderId != null && it.selectedRiderId == riderId) null else riderId
            it.copy(
                selectedRiderId = toggled,
                isFollowingSelf = false
            )
        }
    }

    fun cycleMapStyle() {
        controls.update { it.copy(mapStyle = it.mapStyle.next()) }
    }

    fun requestFitConvoy() {
        controls.update {
            it.copy(
                isFollowingSelf = false,
                fitConvoySignal = it.fitConvoySignal + 1
            )
        }
    }

    fun requestCenterOnSelf() {
        controls.update {
            it.copy(
                isFollowingSelf = true,
                centerSelfSignal = it.centerSelfSignal + 1
            )
        }
    }

    fun requestFocusDestination() {
        controls.update {
            it.copy(
                isFollowingSelf = false,
                selectedRiderId = null,
                focusDestinationSignal = it.focusDestinationSignal + 1
            )
        }
    }

    fun requestFocusMeetup() {
        controls.update {
            it.copy(
                isFollowingSelf = false,
                selectedRiderId = null,
                focusMeetupSignal = it.focusMeetupSignal + 1
            )
        }
    }

    fun requestNavigateToRiderInApp(riderId: String?) {
        controls.update {
            val nextTarget = if (riderId != null && it.navigateTargetRiderId == riderId) null else riderId
            it.copy(
                isFollowingSelf = false,
                selectedRiderId = riderId ?: it.selectedRiderId,
                navigateTargetRiderId = nextTarget
            )
        }
    }

    fun disableAutoFollow() {
        controls.update {
            if (it.isFollowingSelf) it.copy(isFollowingSelf = false) else it
        }
    }

    private fun safeHasLocationPermission(): Boolean {
        return try {
            locationPermissionChecker()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val RECENT_LOCATION_THRESHOLD_MS = 60_000L
        private const val LOCAL_SELF_FALLBACK_ID = "__self__"

        internal fun buildConvoyMapUiState(
            participants: List<Participant>,
            remoteLocations: Map<String, RiderLocation>,
            selfLocation: Location?,
            activeSpeaker: String?,
            connectionState: ConnectionState,
            ride: ActiveRideRepository.ActiveRide?,
            selfId: String?,
            nowMs: Long,
            selectedRiderId: String?,
            navigateTargetRiderId: String? = null,
            mapStyle: ConvoyMapStyle,
            isFollowingSelf: Boolean,
            hasLocationPermission: Boolean,
            fitConvoySignal: Int,
            centerSelfSignal: Int,
            focusDestinationSignal: Int = 0,
            focusMeetupSignal: Int = 0,
            route: ConvoyRoute? = ride?.route,
            nextInstruction: String? = null
        ): ConvoyMapUiState {
            val resolvedSelfId = selfId?.takeIf { it.isNotBlank() } ?: LOCAL_SELF_FALLBACK_ID
            val rideId = ride?.roomId
            val selfLat = selfLocation?.latitude
            val selfLng = selfLocation?.longitude

            val itemsById = linkedMapOf<String, RiderMapItem>()

            // Always put current rider first so they know their own GPS status on the map
            val selfParticipant = participants.firstOrNull { it.identity == resolvedSelfId }
            val selfRemoteFallback = remoteLocations[resolvedSelfId]
            val effectiveSelfLat = selfLat ?: selfRemoteFallback?.lat
            val effectiveSelfLng = selfLng ?: selfRemoteFallback?.lng
            val selfTimestamp = when {
                selfLocation != null -> nowMs
                else -> selfRemoteFallback?.timestamp
            }
            itemsById[resolvedSelfId] = RiderMapItem(
                riderId = resolvedSelfId,
                displayName = ParticipantNameResolver.resolve(
                    identity = resolvedSelfId,
                    livekitName = selfParticipant?.displayName,
                    isCurrentUser = true,
                    rideId = rideId
                ),
                isSelf = true,
                isHost = ride?.isHost == true,
                isSpeaking = activeSpeaker == resolvedSelfId,
                isGhost = selfParticipant?.isGhost ?: false,
                lat = effectiveSelfLat,
                lng = effectiveSelfLng,
                speedMps = selfLocation?.speed ?: selfRemoteFallback?.speed,
                headingDeg = selfLocation?.bearing ?: selfRemoteFallback?.heading,
                locationTimestampMs = selfTimestamp,
                distanceFromSelfMeters = if (effectiveSelfLat != null && effectiveSelfLng != null) 0.0 else null,
                isLocationRecent = selfTimestamp != null && (nowMs - selfTimestamp) <= RECENT_LOCATION_THRESHOLD_MS
            )

            // Add every participant in the LiveKit voice room
            for (participant in participants) {
                if (participant.identity == resolvedSelfId) continue
                val loc = remoteLocations[participant.identity]
                val dist = if (effectiveSelfLat != null && effectiveSelfLng != null && loc != null) {
                    haversineMeters(effectiveSelfLat, effectiveSelfLng, loc.lat, loc.lng)
                } else {
                    null
                }
                itemsById[participant.identity] = RiderMapItem(
                    riderId = participant.identity,
                    displayName = ParticipantNameResolver.resolve(
                        identity = participant.identity,
                        livekitName = participant.displayName,
                        isCurrentUser = false,
                        rideId = rideId
                    ),
                    isSelf = false,
                    isHost = false,
                    isSpeaking = activeSpeaker == participant.identity,
                    isGhost = participant.isGhost,
                    lat = loc?.lat,
                    lng = loc?.lng,
                    speedMps = loc?.speed,
                    headingDeg = loc?.heading,
                    locationTimestampMs = loc?.timestamp,
                    distanceFromSelfMeters = dist,
                    isLocationRecent = loc != null && (nowMs - loc.timestamp) <= RECENT_LOCATION_THRESHOLD_MS
                )
            }

            // Include any rider with a recent remote location packet even if participant sync lags
            for ((locRiderId, loc) in remoteLocations) {
                if (locRiderId == resolvedSelfId || itemsById.containsKey(locRiderId)) continue
                val dist = if (effectiveSelfLat != null && effectiveSelfLng != null) {
                    haversineMeters(effectiveSelfLat, effectiveSelfLng, loc.lat, loc.lng)
                } else {
                    null
                }
                itemsById[locRiderId] = RiderMapItem(
                    riderId = locRiderId,
                    displayName = ParticipantNameResolver.resolve(
                        identity = locRiderId,
                        isCurrentUser = false,
                        rideId = rideId
                    ),
                    isSelf = false,
                    isHost = false,
                    isSpeaking = activeSpeaker == locRiderId,
                    isGhost = false,
                    lat = loc.lat,
                    lng = loc.lng,
                    speedMps = loc.speed,
                    headingDeg = loc.heading,
                    locationTimestampMs = loc.timestamp,
                    distanceFromSelfMeters = dist,
                    isLocationRecent = (nowMs - loc.timestamp) <= RECENT_LOCATION_THRESHOLD_MS
                )
            }

            // Keep self first, then live-location riders, then speaking, then alphabetical
            val sortedRiders = itemsById.values.sortedWith(
                compareByDescending<RiderMapItem> { it.isSelf }
                    .thenByDescending { it.isSpeaking }
                    .thenByDescending { it.hasLocation }
                    .thenBy { it.isGhost }
                    .thenBy { it.displayName.lowercase() }
            )

            val uiRoute = route?.takeIf { it.hasAnyRouteInfo }?.let { r ->
                val originLatLng = if (r.hasOriginCoords) LatLng(r.originLat!!, r.originLng!!) else null
                val destLatLng = if (r.hasDestinationCoords) LatLng(r.destinationLat!!, r.destinationLng!!) else null
                val meetupLatLng = if (r.hasMeetupCoords) LatLng(r.meetupLat!!, r.meetupLng!!) else null
                val poly = r.encodedPolyline?.takeIf { it.isNotBlank() }?.let {
                    GooglePlacesRouteService.decodePolyline(it)
                }.orEmpty()
                val liveDestMeters = if (effectiveSelfLat != null && effectiveSelfLng != null && destLatLng != null) {
                    haversineMeters(effectiveSelfLat, effectiveSelfLng, destLatLng.latitude, destLatLng.longitude).roundToInt()
                } else {
                    r.distanceMeters
                }
                val liveMeetupMeters = if (effectiveSelfLat != null && effectiveSelfLng != null && (meetupLatLng ?: originLatLng) != null) {
                    val target = meetupLatLng ?: originLatLng!!
                    haversineMeters(effectiveSelfLat, effectiveSelfLng, target.latitude, target.longitude).roundToInt()
                } else {
                    null
                }
                ConvoyRouteUiState(
                    originName = r.originName,
                    originLatLng = originLatLng,
                    destinationName = r.destinationName,
                    destinationLatLng = destLatLng,
                    meetupPoint = r.meetupPoint,
                    meetupLatLng = meetupLatLng,
                    polylinePoints = poly,
                    distanceMeters = r.distanceMeters,
                    distanceToDestinationMeters = liveDestMeters,
                    distanceToMeetupMeters = liveMeetupMeters,
                    estimatedDurationMin = r.estimatedDurationMin,
                    nextInstruction = nextInstruction
                )
            }

            return ConvoyMapUiState(
                rideName = ride?.displayName.orEmpty(),
                isOnlineVoiceConnected = connectionState == ConnectionState.CONNECTED,
                hasLocationPermission = hasLocationPermission,
                riders = sortedRiders,
                selectedRiderId = selectedRiderId?.takeIf { id -> sortedRiders.any { it.riderId == id } },
                navigateTargetRiderId = navigateTargetRiderId?.takeIf { id -> sortedRiders.any { it.riderId == id } },
                mapStyle = mapStyle,
                isFollowingSelf = isFollowingSelf,
                fitConvoySignal = fitConvoySignal,
                centerSelfSignal = centerSelfSignal,
                focusDestinationSignal = focusDestinationSignal,
                focusMeetupSignal = focusMeetupSignal,
                route = uiRoute
            )
        }

        private fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        private val REMOTE_BLIP_COLORS = intArrayOf(
            argb(230, 81, 0),    // Deep Ember Orange
            argb(0, 121, 107),   // Deep Teal
            argb(106, 27, 154),  // Royal Plum
            argb(173, 20, 87),   // Magenta Berry
            argb(191, 54, 12)    // Burnt Sienna
        )

        val SELF_BLIP_COLOR_ARGB: Int = argb(21, 101, 192)       // Royal Blue (reserved for You)
        val SPEAKING_RING_COLOR_ARGB: Int = argb(46, 125, 50)   // Bright Moss Green speaking halo
        val STALE_BLIP_COLOR_ARGB: Int = argb(122, 86, 0)       // Warm Ochre stale/ghost
        val START_PIN_COLOR_ARGB: Int = argb(46, 125, 50)       // Green Start pin
        val MEETUP_PIN_COLOR_ARGB: Int = argb(217, 119, 6)      // Amber Gold Meet-up pin
        val DEST_PIN_COLOR_ARGB: Int = argb(198, 40, 40)        // Crimson Destination pin

        fun riderBlipColorArgb(rider: RiderMapItem): Int {
            if (rider.isSelf) return SELF_BLIP_COLOR_ARGB
            if (rider.isGhost || (rider.hasLocation && !rider.isLocationRecent)) return STALE_BLIP_COLOR_ARGB
            val idx = abs(rider.riderId.hashCode()) % REMOTE_BLIP_COLORS.size
            return REMOTE_BLIP_COLORS[idx]
        }

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val earthRadiusMeters = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val a = sin(dLat / 2).pow(2) +
                cos(rLat1) * cos(rLat2) * sin(dLng / 2).pow(2)
            val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
            return earthRadiusMeters * c
        }

        fun buildBoundsForRiders(
            riders: List<RiderMapItem>,
            route: ConvoyRouteUiState? = null
        ): LatLngBounds? {
            val points = buildList {
                addAll(riders.mapNotNull { it.latLng })
                route?.originLatLng?.let { add(it) }
                route?.meetupLatLng?.let { add(it) }
                route?.destinationLatLng?.let { add(it) }
                val poly = route?.polylinePoints.orEmpty()
                if (poly.isNotEmpty()) {
                    val step = (poly.size / 16).coerceAtLeast(1)
                    for (i in poly.indices step step) {
                        add(poly[i])
                    }
                    add(poly.last())
                }
            }
            if (points.size < 2) return null
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            return builder.build()
        }
    }
}

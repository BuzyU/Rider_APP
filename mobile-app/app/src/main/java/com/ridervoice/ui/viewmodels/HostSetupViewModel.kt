package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.models.ConvoyCreateRequest
import com.ridervoice.models.ConvoyRoute
import com.ridervoice.navigation.ComputedDirections
import com.ridervoice.navigation.GooglePlacesRouteService
import com.ridervoice.navigation.PlaceSuggestion
import com.ridervoice.navigation.ResolvedPlace
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.services.LocationService
import com.ridervoice.state.ActiveRideRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HostSetupViewModel private constructor(
    private val apiService: ApiService,
    private val securePreferences: SecurePreferences,
    private val activeRideRepository: ActiveRideRepository,
    private val routePlacesService: GooglePlacesRouteService?,
    private val locationService: LocationService?,
    @Suppress("UNUSED_PARAMETER") privateCtorMarker: Boolean
) : ViewModel() {

    @Inject
    constructor(
        apiService: ApiService,
        securePreferences: SecurePreferences,
        activeRideRepository: ActiveRideRepository,
        routePlacesService: GooglePlacesRouteService,
        locationService: LocationService
    ) : this(
        apiService = apiService,
        securePreferences = securePreferences,
        activeRideRepository = activeRideRepository,
        routePlacesService = routePlacesService,
        locationService = locationService,
        privateCtorMarker = false
    )

    constructor(
        apiService: ApiService,
        securePreferences: SecurePreferences,
        activeRideRepository: ActiveRideRepository
    ) : this(
        apiService = apiService,
        securePreferences = securePreferences,
        activeRideRepository = activeRideRepository,
        routePlacesService = null,
        locationService = null,
        privateCtorMarker = false
    )

    sealed class CreateRideState {
        object Draft : CreateRideState()
        object Submitting : CreateRideState()
        data class UnknownOutcome(val clientRequestId: String, val message: String) : CreateRideState()
        data class Succeeded(val convoyName: String) : CreateRideState()
        data class Conflict(val message: String) : CreateRideState()
    }

    companion object {
        const val KEY_PENDING_CREATE_REQUEST_ID = "pending_create_request_id"
        const val KEY_PENDING_CREATE_NAME = "pending_create_name"
    }

    private val _createState = MutableStateFlow<CreateRideState>(CreateRideState.Draft)
    val createState = _createState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _createdConvoyName = MutableStateFlow<String?>(null)
    val createdConvoyName = _createdConvoyName.asStateFlow()

    private val _originSuggestions = MutableStateFlow<List<PlaceSuggestion>>(emptyList())
    val originSuggestions = _originSuggestions.asStateFlow()

    private val _destinationSuggestions = MutableStateFlow<List<PlaceSuggestion>>(emptyList())
    val destinationSuggestions = _destinationSuggestions.asStateFlow()

    private val _isSearchingOrigin = MutableStateFlow(false)
    val isSearchingOrigin = _isSearchingOrigin.asStateFlow()

    private val _isSearchingDestination = MutableStateFlow(false)
    val isSearchingDestination = _isSearchingDestination.asStateFlow()

    private val _selectedOrigin = MutableStateFlow<ResolvedPlace?>(null)
    val selectedOrigin = _selectedOrigin.asStateFlow()

    private val _selectedDestination = MutableStateFlow<ResolvedPlace?>(null)
    val selectedDestination = _selectedDestination.asStateFlow()

    private val _routePreview = MutableStateFlow<ComputedDirections?>(null)
    val routePreview = _routePreview.asStateFlow()

    private var originSearchJob: Job? = null
    private var destinationSearchJob: Job? = null
    private var routePreviewJob: Job? = null

    fun onOriginQueryChanged(query: String) {
        val clean = query.trim()
        if (_selectedOrigin.value?.name != clean) {
            _selectedOrigin.value = null
            _routePreview.value = null
        }
        originSearchJob?.cancel()
        if (clean.length < 2 || routePlacesService == null) {
            _originSuggestions.value = emptyList()
            _isSearchingOrigin.value = false
            return
        }
        originSearchJob = viewModelScope.launch {
            _isSearchingOrigin.value = true
            delay(250L)
            val loc = locationService?.currentLocation?.value
            val suggestions = routePlacesService.autocompletePlaces(clean, loc?.latitude, loc?.longitude)
            _originSuggestions.value = suggestions
            _isSearchingOrigin.value = false
        }
    }

    fun onDestinationQueryChanged(query: String) {
        val clean = query.trim()
        if (_selectedDestination.value?.name != clean) {
            _selectedDestination.value = null
            _routePreview.value = null
        }
        destinationSearchJob?.cancel()
        if (clean.length < 2 || routePlacesService == null) {
            _destinationSuggestions.value = emptyList()
            _isSearchingDestination.value = false
            return
        }
        destinationSearchJob = viewModelScope.launch {
            _isSearchingDestination.value = true
            delay(250L)
            val loc = locationService?.currentLocation?.value
            val suggestions = routePlacesService.autocompletePlaces(clean, loc?.latitude, loc?.longitude)
            _destinationSuggestions.value = suggestions
            _isSearchingDestination.value = false
        }
    }

    fun selectOriginSuggestion(suggestion: PlaceSuggestion, onTextResolved: (String) -> Unit) {
        originSearchJob?.cancel()
        _originSuggestions.value = emptyList()
        _isSearchingOrigin.value = false
        onTextResolved(suggestion.fullText)
        viewModelScope.launch {
            val resolved = routePlacesService?.resolveSuggestion(suggestion)
                ?: suggestion.lat?.let { lat ->
                    suggestion.lng?.let { lng -> ResolvedPlace(suggestion.fullText, lat, lng) }
                }
            if (resolved != null) {
                _selectedOrigin.value = resolved
                onTextResolved(resolved.name)
                refreshRoutePreview()
            }
        }
    }

    fun selectDestinationSuggestion(suggestion: PlaceSuggestion, onTextResolved: (String) -> Unit) {
        destinationSearchJob?.cancel()
        _destinationSuggestions.value = emptyList()
        _isSearchingDestination.value = false
        onTextResolved(suggestion.fullText)
        viewModelScope.launch {
            val resolved = routePlacesService?.resolveSuggestion(suggestion)
                ?: suggestion.lat?.let { lat ->
                    suggestion.lng?.let { lng -> ResolvedPlace(suggestion.fullText, lat, lng) }
                }
            if (resolved != null) {
                _selectedDestination.value = resolved
                onTextResolved(resolved.name)
                refreshRoutePreview()
            }
        }
    }

    fun dismissSuggestions() {
        _originSuggestions.value = emptyList()
        _destinationSuggestions.value = emptyList()
    }

    fun useCurrentLocationAsOrigin(onResolved: (String) -> Unit, onUnavailable: () -> Unit = {}) {
        locationService?.startTracking()
        val loc = locationService?.currentLocation?.value
        if (loc == null) {
            onUnavailable()
            return
        }
        _originSuggestions.value = emptyList()
        viewModelScope.launch {
            val label = routePlacesService?.reverseGeocode(loc.latitude, loc.longitude)
                ?: String.format(java.util.Locale.getDefault(), "%.4f, %.4f", loc.latitude, loc.longitude)
            val resolved = ResolvedPlace(name = label, lat = loc.latitude, lng = loc.longitude)
            _selectedOrigin.value = resolved
            onResolved(label)
            refreshRoutePreview()
        }
    }

    fun swapEndpoints() {
        val oldOrigin = _selectedOrigin.value
        val oldDest = _selectedDestination.value
        _selectedOrigin.value = oldDest
        _selectedDestination.value = oldOrigin
        _originSuggestions.value = emptyList()
        _destinationSuggestions.value = emptyList()
        refreshRoutePreview()
    }

    private fun refreshRoutePreview() {
        val start = _selectedOrigin.value
        val end = _selectedDestination.value
        routePreviewJob?.cancel()
        if (start == null || end == null || routePlacesService == null) {
            _routePreview.value = null
            return
        }
        routePreviewJob = viewModelScope.launch {
            _routePreview.value = routePlacesService.fetchRoute(start.lat, start.lng, end.lat, end.lng)
        }
    }

    fun getOrCreateClientRequestId(): String {
        val existing = securePreferences.getString(KEY_PENDING_CREATE_REQUEST_ID, "")
        if (existing.isNotBlank()) return existing
        val newId = UUID.randomUUID().toString()
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, newId)
        return newId
    }

    fun clearPendingAttempt() {
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, "")
        securePreferences.saveString(KEY_PENDING_CREATE_NAME, "")
        _createState.value = CreateRideState.Draft
    }

    private fun savePendingAttempt(requestId: String, name: String) {
        securePreferences.saveString(KEY_PENDING_CREATE_REQUEST_ID, requestId)
        securePreferences.saveString(KEY_PENDING_CREATE_NAME, name)
    }

    fun onNavigationHandled() {
        _createdConvoyName.value = null
        _createState.value = CreateRideState.Draft
    }

    fun createConvoy(
        convoyName: String,
        origin: String?,
        destination: String?,
        meetupPoint: String?,
        estimatedDurationMin: Int? = null,
        diagnosticsEnabled: Boolean = false,
        clientRequestId: String? = null
    ) {
        // Single-flight guard: prevent concurrent duplicate requests
        if (_createState.value is CreateRideState.Submitting || _isLoading.value) {
            return
        }

        val activeRequestId = clientRequestId ?: getOrCreateClientRequestId()
        savePendingAttempt(activeRequestId, convoyName)

        _createState.value = CreateRideState.Submitting
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val cleanOrigin = origin?.trim()?.takeIf { it.isNotBlank() }
                val cleanDest = destination?.trim()?.takeIf { it.isNotBlank() }
                val cleanMeetup = meetupPoint?.trim()?.takeIf { it.isNotBlank() }

                val resolvedStartDeferred = async {
                    _selectedOrigin.value?.takeIf { cleanOrigin == null || it.name.equals(cleanOrigin, ignoreCase = true) }
                        ?: if (cleanOrigin != null && routePlacesService != null) {
                            withTimeoutOrNull(1_500L) { routePlacesService.geocodeName(cleanOrigin) }
                        } else null
                }
                val resolvedDestDeferred = async {
                    _selectedDestination.value?.takeIf { cleanDest == null || it.name.equals(cleanDest, ignoreCase = true) }
                        ?: if (cleanDest != null && routePlacesService != null) {
                            withTimeoutOrNull(1_500L) { routePlacesService.geocodeName(cleanDest) }
                        } else null
                }
                val startPlace = resolvedStartDeferred.await()
                val endPlace = resolvedDestDeferred.await()

                val directions = _routePreview.value ?: if (startPlace != null && endPlace != null && routePlacesService != null) {
                    withTimeoutOrNull(1_500L) {
                        routePlacesService.fetchRoute(startPlace.lat, startPlace.lng, endPlace.lat, endPlace.lng)
                    }
                } else null

                val effectiveDurationMin = estimatedDurationMin ?: directions?.durationMinutes
                val convoyRoute = ConvoyRoute(
                    originName = cleanOrigin ?: startPlace?.name,
                    originLat = startPlace?.lat,
                    originLng = startPlace?.lng,
                    destinationName = cleanDest ?: endPlace?.name,
                    destinationLat = endPlace?.lat,
                    destinationLng = endPlace?.lng,
                    meetupPoint = cleanMeetup,
                    estimatedDurationMin = effectiveDurationMin,
                    distanceMeters = directions?.distanceMeters,
                    encodedPolyline = directions?.encodedPolyline
                ).takeIf { it.hasAnyRouteInfo }

                val req = ConvoyCreateRequest(
                    convoyName = convoyName,
                    origin = cleanOrigin ?: startPlace?.name,
                    destination = cleanDest ?: endPlace?.name,
                    estimatedDurationMin = effectiveDurationMin,
                    meetupPoint = cleanMeetup,
                    diagnosticsEnabled = diagnosticsEnabled,
                    clientRequestId = activeRequestId
                )
                val response = apiService.createConvoy(req)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    activeRideRepository.enterLobby(
                        roomId = body.roomId,
                        name = body.convoyName,
                        isHost = true,
                        joinCode = body.joinCode,
                        route = convoyRoute
                    )
                    clearPendingAttempt()
                    _createdConvoyName.value = body.convoyName
                    _createState.value = CreateRideState.Succeeded(body.convoyName)
                } else {
                    val raw = response.errorBody()?.string()
                    val errorMsg = parseConvoyError(raw, response.code())
                    _error.value = errorMsg
                    if (response.code() == 409) {
                        _createState.value = CreateRideState.Conflict(errorMsg)
                    } else {
                        _createState.value = CreateRideState.UnknownOutcome(activeRequestId, errorMsg)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "Network error: ${e.localizedMessage ?: "Please check your connection and retry."}"
                _error.value = errorMsg
                _createState.value = CreateRideState.UnknownOutcome(activeRequestId, errorMsg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseConvoyError(raw: String?, code: Int): String {
        if (!raw.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(raw)
                if (json.has("error")) {
                    val errorMsg = json.getString("error")
                    if (errorMsg.contains("Internal server error", ignoreCase = true)) {
                        return "The server encountered a problem creating your ride. Please try again."
                    }
                    return errorMsg
                }
            } catch (_: Exception) {
                if (raw.contains("Internal server error", ignoreCase = true)) {
                    return "The server encountered a problem creating your ride. Please try again."
                }
            }
        }
        return when (code) {
            400 -> "Invalid ride details. Please check the ride name and try again."
            401 -> "Your session has expired. Please sign in again."
            409 -> "A ride with this name already exists. Please choose a different name."
            in 500..599 -> "Server trouble. Please try again shortly."
            else -> "Failed to create ride (code $code). Please retry."
        }
    }

    fun isDiagnosticsConsentShown(): Boolean = securePreferences.isDiagnosticsConsentShown()
    fun setDiagnosticsConsentShown(shown: Boolean) = securePreferences.setDiagnosticsConsentShown(shown)
}

package com.ridervoice.state

import android.content.Context
import com.ridervoice.models.ConvoyRoute
import com.ridervoice.models.PostRideSnapshot
import com.ridervoice.models.RefreshTokenRequest
import com.ridervoice.models.TokenPayload
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.utils.JwtRoomClaimDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

typealias ActiveRideState = ActiveRideRepository.ActiveRide

@Singleton
class ActiveRideRepository @Inject constructor(
    private val prefs: SecurePreferences?,
    private val api: ApiService,
    @ApplicationContext private val ctx: Context? = null
) {
    enum class Phase { LOBBY, LIVE, ENDING }
    enum class ClearReason { LEFT, ENDED_BY_HOST, REMOVED, SIGNED_OUT, NOT_FOUND, TOKEN_FAILURE }

    data class ActiveRide(
        val roomId: String,
        val displayName: String,
        val isHost: Boolean,
        val phase: Phase,
        val lkRoomName: String? = null,
        val livekitUrl: String? = null,
        val token: String? = null,
        val tokenExpEpochSec: Long? = null,
        val diagnosticsEnabled: Boolean = false,
        val testNumber: Int? = null,
        val joinCode: String? = null,
        val meshConfig: com.ridervoice.mesh.RoomMeshConfig? = null,
        val voiceServiceError: String? = null,
        val convoyMembershipSnapshot: com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot? = null,
        val route: ConvoyRoute? = null,
        val hostUserId: String? = null
    )

    fun setMeshConfig(config: com.ridervoice.mesh.RoomMeshConfig) {
        _state.update { current ->
            val updated = current?.copy(meshConfig = config)
            if (updated != null) persist(updated)
            updated
        }
    }

    fun setConvoyMembershipSnapshot(snapshot: com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot?) {
        _state.update { current ->
            current?.copy(convoyMembershipSnapshot = snapshot)
        }
    }

    private val _state = MutableStateFlow<ActiveRide?>(restoreFromPrefs())
    val state: StateFlow<ActiveRide?> = _state.asStateFlow()
    private val _lastEnded = MutableStateFlow<PostRideSnapshot?>(null)
    val lastEnded: StateFlow<PostRideSnapshot?> = _lastEnded.asStateFlow()
    private val _voiceServiceError = MutableStateFlow<String?>(null)
    val voiceServiceError: StateFlow<String?> = _voiceServiceError.asStateFlow()

    fun setVoiceServiceError(error: String?) {
        _voiceServiceError.value = error
        _state.update { current ->
            current?.copy(voiceServiceError = error)
        }
    }

    fun enterLobby(
        roomId: String,
        name: String,
        isHost: Boolean,
        joinCode: String? = null,
        lkRoomName: String? = null,
        route: ConvoyRoute? = null,
        hostUserId: String? = null
    ) {
        _state.update { current ->
            val sameRoom = current != null && current.roomId == roomId
            val existingRoute = if (sameRoom) current?.route else null
            val mergedRoute = when {
                existingRoute != null && route != null -> existingRoute.mergeWith(route)
                route != null -> route
                else -> existingRoute
            }
            val effectiveHostUserId = hostUserId?.takeIf { it.isNotBlank() }
                ?: if (sameRoom) current?.hostUserId else null
            val ride = ActiveRide(
                roomId = roomId,
                displayName = name,
                isHost = isHost,
                phase = if (sameRoom && current?.phase == Phase.LIVE) Phase.LIVE else Phase.LOBBY,
                lkRoomName = lkRoomName ?: if (sameRoom) current?.lkRoomName else null,
                livekitUrl = if (sameRoom) current?.livekitUrl else null,
                token = if (sameRoom) current?.token else null,
                tokenExpEpochSec = if (sameRoom) current?.tokenExpEpochSec else null,
                diagnosticsEnabled = if (sameRoom) current?.diagnosticsEnabled ?: false else false,
                testNumber = if (sameRoom) current?.testNumber else null,
                joinCode = joinCode ?: if (sameRoom) current?.joinCode else null,
                meshConfig = if (sameRoom) current?.meshConfig else null,
                route = mergedRoute,
                hostUserId = effectiveHostUserId
            )
            persist(ride)
            ride
        }
    }

    fun updateRoute(route: ConvoyRoute?) {
        if (route == null || !route.hasAnyRouteInfo) return
        _state.update { current ->
            if (current == null) return@update null
            val merged = current.route?.mergeWith(route) ?: route
            val updated = current.copy(route = merged)
            persist(updated)
            updated
        }
    }

    fun applyTokenPayload(p: TokenPayload) {
        // M8a: If cleared or rideId does not match incoming payload, do not resurrect ghost ride
        _state.update { current ->
            if (current == null) return@update null
            val payloadRoomId = p.roomId
            if (!payloadRoomId.isNullOrBlank() && current.roomId.isNotBlank() && current.roomId != payloadRoomId) {
                return@update current
            }
            val updated = current.copy(
                roomId = p.roomId ?: current.roomId,
                displayName = p.roomName ?: current.displayName,
                lkRoomName = p.lkRoomName ?: current.lkRoomName,
                livekitUrl = p.livekitUrl ?: current.livekitUrl,
                token = p.token,
                tokenExpEpochSec = p.expiresAtEpochSec ?: current.tokenExpEpochSec,
                diagnosticsEnabled = p.diagnosticsEnabled,
                testNumber = p.testNumber ?: current.testNumber
            )
            persist(updated)
            updated
        }
    }

    fun updateHost(isHost: Boolean) {
        _state.update { current ->
            if (current == null) return@update null
            val updated = current.copy(isHost = isHost)
            persist(updated)
            updated
        }
    }

    fun goLive() {
        _state.update { current ->
            if (current == null) return@update null
            val updated = current.copy(phase = Phase.LIVE)
            persist(updated)
            updated
        }
    }

    suspend fun ensureFreshToken(): Result<String> {
        val current = _state.value ?: return Result.failure(IllegalStateException("No active ride"))
        val existingToken = current.token
        val nowSec = System.currentTimeMillis() / 1000

        if (!existingToken.isNullOrBlank()) {
            val check = JwtRoomClaimDecoder.validate(
                token = existingToken,
                expectedRoomId = current.roomId,
                expectedRoomName = current.displayName,
                nowEpochSeconds = nowSec,
                skewSeconds = 60,
                refreshWindowSeconds = 300,
                expectedLkRoomName = current.lkRoomName
            )
            if (check == JwtRoomClaimDecoder.TokenCheck.VALID) {
                return Result.success(existingToken)
            }
        }

        return try {
            val res = api.refreshToken(
                RefreshTokenRequest(
                    roomId = current.roomId.ifBlank { null },
                    roomName = current.displayName.ifBlank { null }
                )
            )
            val body = res.body()
            if (res.isSuccessful && body != null) {
                val expSec = body.expiresAt?.let { parseEpochSec(it) } ?: JwtRoomClaimDecoder.extractExpiry(body.token)
                applyTokenPayload(
                    TokenPayload(
                        token = body.token,
                        livekitUrl = body.livekitUrl,
                        roomId = body.roomId ?: current.roomId,
                        roomName = body.roomName ?: current.displayName,
                        lkRoomName = body.lkRoomName ?: current.lkRoomName,
                        expiresAtEpochSec = expSec,
                        diagnosticsEnabled = body.diagnosticsEnabled,
                        testNumber = body.testNumber
                    )
                )
                Result.success(body.token)
            } else {
                val code = res.code()
                val errBody = try { res.errorBody()?.string() } catch (_: Exception) { null }
                when (code) {
                    403 -> {
                        clear(ClearReason.REMOVED)
                        val msg = if (errBody?.contains("NOT_A_MEMBER") == true) "NOT_A_MEMBER" else "REMOVED_FROM_CONVOY"
                        Result.failure(Exception(msg))
                    }
                    410 -> {
                        clear(ClearReason.ENDED_BY_HOST)
                        Result.failure(Exception("CONVOY_ENDED"))
                    }
                    404 -> {
                        var reallyNotFound = true
                        try {
                            val statusRes = api.getLobbyStatus(current.roomId)
                            if (statusRes.isSuccessful && statusRes.body() != null) {
                                reallyNotFound = false
                            }
                        } catch (_: Exception) {}
                        if (reallyNotFound) {
                            clear(ClearReason.ENDED_BY_HOST)
                            Result.failure(Exception("ROOM_NOT_FOUND"))
                        } else {
                            Result.failure(Exception("Token refresh failed: transient 404"))
                        }
                    }
                    else -> Result.failure(Exception("Token refresh failed with HTTP $code"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clear(reason: ClearReason) {
        val current = _state.value
        if (current != null) {
            _lastEnded.value = PostRideSnapshot(
                convoyName = current.displayName,
                testNumber = current.testNumber,
                diagnosticsEnabled = current.diagnosticsEnabled,
                endedBy = reason.name
            )
        }
        _state.update { null }
        _voiceServiceError.value = null
        prefs?.saveString("active_ride_route_json", "")
        prefs?.clearActiveRide()
    }

    suspend fun validateOnStart() {
        val savedRoomId = prefs?.getActiveRideRoom() ?: return
        val savedLkRoom = prefs?.getActiveRideLkRoom()
        val isHost = prefs?.isActiveRideHost() ?: false
        val phaseName = prefs?.getString("active_ride_phase", Phase.LOBBY.name) ?: Phase.LOBBY.name
        val phase = runCatching { Phase.valueOf(phaseName) }.getOrDefault(Phase.LOBBY)
        val restoredConfig = _state.value?.meshConfig ?: loadPersistedMeshConfig()

        try {
            val res = api.getLobbyStatus(savedRoomId)
            val code = res.code()
            if (code == 410) {
                clear(ClearReason.ENDED_BY_HOST)
            } else if (code == 404) {
                clear(ClearReason.NOT_FOUND)
            } else if (code == 403) {
                clear(ClearReason.REMOVED)
            } else {
                val status = res.body()
                if (res.isSuccessful && status != null) {
                    val serverMeshEnabled = status.offlineMeshEnabled
                    val effectiveConfig = when {
                        restoredConfig != null -> restoredConfig.copy(
                            offlineMeshEnabled = serverMeshEnabled,
                            optOut = restoredConfig.optOut || !serverMeshEnabled
                        )
                        !serverMeshEnabled -> com.ridervoice.mesh.RoomMeshConfig.DISABLED
                        else -> null
                    }
                    val serverRoute = ConvoyRoute(
                        originName = status.origin,
                        destinationName = status.destination,
                        meetupPoint = status.meetupPoint,
                        estimatedDurationMin = status.estimatedDurationMin
                    ).takeIf { it.hasAnyRouteInfo }
                    val existingRoute = _state.value?.takeIf { it.roomId == (status.roomId ?: savedRoomId) }?.route
                        ?: restoredRouteIfSameRoom(savedRoomId)
                    val mergedRoute = when {
                        existingRoute != null && serverRoute != null -> existingRoute.mergeWith(serverRoute)
                        serverRoute != null -> serverRoute
                        else -> existingRoute
                    }
                    val activeRide = ActiveRide(
                        roomId = status.roomId ?: savedRoomId,
                        displayName = status.convoyName ?: savedRoomId,
                        isHost = status.isHost ?: isHost,
                        phase = if (status.isRideStarted == true) Phase.LIVE else phase,
                        lkRoomName = status.lkRoomName ?: savedLkRoom,
                        diagnosticsEnabled = status.diagnosticsEnabled ?: false,
                        joinCode = status.joinCode,
                        meshConfig = effectiveConfig,
                        route = mergedRoute,
                        hostUserId = status.host?.id?.takeIf { it.isNotBlank() }
                            ?: prefs?.getString("active_ride_host_user_id", "")?.takeIf { it.isNotBlank() }
                    )
                    persist(activeRide)
                    _state.update { activeRide }
                }
            }
        } catch (e: Exception) {
            // M8c: Offline relaunch: restore optimistically from saved preferences if state is null
            _state.update { current ->
                current ?: ActiveRide(
                    roomId = savedRoomId,
                    displayName = prefs?.getString("active_ride_display_name", savedRoomId) ?: savedRoomId,
                    isHost = isHost,
                    phase = phase,
                    lkRoomName = savedLkRoom,
                    meshConfig = restoredConfig,
                    hostUserId = prefs?.getString("active_ride_host_user_id", "")?.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private fun loadPersistedMeshConfig(): com.ridervoice.mesh.RoomMeshConfig? {
        val p = prefs ?: return null
        if (!p.getBoolean("active_ride_mesh_present", false)) return null
        val statusName = p.getString(
            "active_ride_mesh_status",
            com.ridervoice.mesh.offline.OfflineConfigStatus.NOT_STARTED.name
        )
        val status = runCatching {
            com.ridervoice.mesh.offline.OfflineConfigStatus.valueOf(statusName)
        }.getOrDefault(com.ridervoice.mesh.offline.OfflineConfigStatus.NOT_STARTED)
        val linkLevelName = p.getString(
            "active_ride_mesh_link_level",
            com.ridervoice.mesh.offline.RiderLinkLevel.AUTO_P2P.name
        )
        val linkLevel = runCatching {
            com.ridervoice.mesh.offline.RiderLinkLevel.valueOf(linkLevelName)
        }.getOrDefault(com.ridervoice.mesh.offline.RiderLinkLevel.AUTO_P2P)
        return com.ridervoice.mesh.RoomMeshConfig(
            offlineMeshEnabled = p.getBoolean("active_ride_mesh_enabled", true),
            automaticFallbackEnabled = p.getBoolean("active_ride_mesh_auto", true),
            warmStandbyEnabled = p.getBoolean("active_ride_mesh_warm", true),
            configCompleted = p.getBoolean("active_ride_mesh_completed", false),
            status = status,
            optOut = p.getBoolean("active_ride_mesh_opt_out", false),
            linkLevel = linkLevel
        )
    }

    private fun restoreFromPrefs(): ActiveRide? {
        val savedRoomId = prefs?.getActiveRideRoom()?.takeIf { it.isNotBlank() } ?: return null
        val savedDisplayName = prefs?.getString("active_ride_display_name", savedRoomId)?.takeIf { it.isNotBlank() } ?: savedRoomId
        val savedLkRoom = prefs?.getActiveRideLkRoom()
        val isHost = prefs?.isActiveRideHost() ?: false
        val phaseName = prefs?.getString("active_ride_phase", Phase.LOBBY.name) ?: Phase.LOBBY.name
        val phase = runCatching { Phase.valueOf(phaseName) }.getOrDefault(Phase.LOBBY)
        val cfg = loadPersistedMeshConfig()
        val route = loadPersistedRoute()
        val savedHostUserId = prefs?.getString("active_ride_host_user_id", "")?.takeIf { it.isNotBlank() }
        return ActiveRide(
            roomId = savedRoomId,
            displayName = savedDisplayName,
            isHost = isHost,
            phase = phase,
            lkRoomName = savedLkRoom,
            meshConfig = cfg,
            route = route,
            hostUserId = savedHostUserId
        )
    }

    private fun restoredRouteIfSameRoom(expectedRoomId: String): ConvoyRoute? {
        val current = _state.value
        if (current != null && current.roomId == expectedRoomId) return current.route
        val restoredRoom = prefs?.getActiveRideRoom()?.takeIf { it == expectedRoomId } ?: return null
        return if (restoredRoom == expectedRoomId) loadPersistedRoute() else null
    }

    private fun loadPersistedRoute(): ConvoyRoute? {
        val raw = prefs?.getString("active_ride_route_json", "")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            com.google.gson.Gson().fromJson(raw, ConvoyRoute::class.java)?.takeIf { it.hasAnyRouteInfo }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(ride: ActiveRide) {
        prefs?.saveActiveRide(ride.roomId, ride.isHost, ride.lkRoomName)
        prefs?.saveString("active_ride_display_name", ride.displayName)
        prefs?.saveString("active_ride_phase", ride.phase.name)
        prefs?.saveString("active_ride_host_user_id", ride.hostUserId ?: "")
        val cfg = ride.meshConfig
        if (cfg != null) {
            prefs?.saveBoolean("active_ride_mesh_present", true)
            prefs?.saveBoolean("active_ride_mesh_enabled", cfg.offlineMeshEnabled)
            prefs?.saveBoolean("active_ride_mesh_auto", cfg.automaticFallbackEnabled)
            prefs?.saveBoolean("active_ride_mesh_warm", cfg.warmStandbyEnabled)
            prefs?.saveBoolean("active_ride_mesh_completed", cfg.configCompleted)
            prefs?.saveBoolean("active_ride_mesh_opt_out", cfg.optOut)
            prefs?.saveString("active_ride_mesh_status", cfg.status.name)
            prefs?.saveString("active_ride_mesh_link_level", cfg.linkLevel.name)
        } else {
            prefs?.saveBoolean("active_ride_mesh_present", false)
        }
        val routeJson = ride.route?.takeIf { it.hasAnyRouteInfo }?.let {
            runCatching { com.google.gson.Gson().toJson(it) }.getOrNull()
        } ?: ""
        prefs?.saveString("active_ride_route_json", routeJson)
    }

    private fun parseEpochSec(isoString: String): Long? {
        return try {
            Instant.parse(isoString).epochSecond
        } catch (e: Exception) {
            null
        }
    }
}

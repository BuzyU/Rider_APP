package com.ridervoice.state

import android.content.Context
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
        val livekitUrl: String? = null,
        val token: String? = null,
        val tokenExpEpochSec: Long? = null,
        val diagnosticsEnabled: Boolean = false,
        val testNumber: Int? = null,
        val joinCode: String? = null
    )

    private val _state = MutableStateFlow<ActiveRide?>(null)
    val state: StateFlow<ActiveRide?> = _state.asStateFlow()
    private val _lastEnded = MutableStateFlow<PostRideSnapshot?>(null)
    val lastEnded: StateFlow<PostRideSnapshot?> = _lastEnded.asStateFlow()

    fun enterLobby(roomId: String, name: String, isHost: Boolean, joinCode: String? = null) {
        val ride = ActiveRide(
            roomId = roomId,
            displayName = name,
            isHost = isHost,
            phase = Phase.LOBBY,
            joinCode = joinCode
        )
        _state.update { ride }
        persist(ride)
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
                refreshWindowSeconds = 300
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
            if (res.isSuccessful && res.body() != null) {
                val body = res.body()!!
                val expSec = body.expiresAt?.let { parseEpochSec(it) } ?: JwtRoomClaimDecoder.extractExpiry(body.token)
                applyTokenPayload(
                    TokenPayload(
                        token = body.token,
                        livekitUrl = body.livekitUrl,
                        roomId = body.roomId ?: current.roomId,
                        roomName = body.roomName ?: current.displayName,
                        expiresAtEpochSec = expSec,
                        diagnosticsEnabled = body.diagnosticsEnabled,
                        testNumber = body.testNumber
                    )
                )
                Result.success(body.token)
            } else {
                val code = res.code()
                when (code) {
                    403 -> {
                        clear(ClearReason.REMOVED)
                        Result.failure(Exception("REMOVED_FROM_CONVOY"))
                    }
                    410, 404 -> {
                        clear(ClearReason.ENDED_BY_HOST)
                        Result.failure(Exception("CONVOY_ENDED"))
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
        prefs?.clearActiveRide()
    }

    suspend fun validateOnStart() {
        val savedRoomId = prefs?.getActiveRideRoom() ?: return
        val isHost = prefs?.isActiveRideHost() ?: false
        val phaseName = prefs?.getString("active_ride_phase", Phase.LOBBY.name) ?: Phase.LOBBY.name
        val phase = runCatching { Phase.valueOf(phaseName) }.getOrDefault(Phase.LOBBY)

        try {
            val res = api.getLobbyStatus(savedRoomId)
            val code = res.code()
            if (code == 410 || code == 404) {
                clear(ClearReason.NOT_FOUND)
            } else if (code == 403) {
                clear(ClearReason.REMOVED)
            } else if (res.isSuccessful && res.body() != null) {
                val status = res.body()!!
                val activeRide = ActiveRide(
                    roomId = status.roomId ?: savedRoomId,
                    displayName = status.convoyName ?: savedRoomId,
                    isHost = status.isHost ?: isHost,
                    phase = if (status.isRideStarted == true) Phase.LIVE else phase,
                    diagnosticsEnabled = status.diagnosticsEnabled ?: false,
                    joinCode = status.joinCode
                )
                _state.update { activeRide }
            }
        } catch (e: Exception) {
            // M8c: Offline relaunch: restore optimistically from saved preferences if state is null
            _state.update { current ->
                current ?: ActiveRide(
                    roomId = savedRoomId,
                    displayName = savedRoomId,
                    isHost = isHost,
                    phase = phase
                )
            }
        }
    }

    private fun persist(ride: ActiveRide) {
        prefs?.saveActiveRide(ride.roomId, ride.isHost)
        prefs?.saveString("active_ride_phase", ride.phase.name)
    }

    private fun parseEpochSec(isoString: String): Long? {
        return try {
            Instant.parse(isoString).epochSecond
        } catch (e: Exception) {
            null
        }
    }
}

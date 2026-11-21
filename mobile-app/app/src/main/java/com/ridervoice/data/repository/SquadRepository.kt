package com.ridervoice.data.repository

import com.ridervoice.models.AcceptFriendRequestBody
import com.ridervoice.models.DeclineFriendRequestBody
import com.ridervoice.models.Friend
import com.ridervoice.models.FriendRequest
import com.ridervoice.models.IncomingSquadRequest
import com.ridervoice.models.InviteRespondRequest
import com.ridervoice.models.JoinTokenRequest
import com.ridervoice.models.ProfileResponse
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RiderRelation
import com.ridervoice.models.RiderSearchState
import com.ridervoice.models.SentSquadRequest
import com.ridervoice.models.SentSquadRequestDto
import com.ridervoice.models.StartRideResponse
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SquadSnapshot(
    val userId: String? = null,
    val hasLoadedOnce: Boolean = false,
    val lastLoadedAtMillis: Long = 0L,
    val friends: List<Friend> = emptyList(),
    val incomingRequests: List<IncomingSquadRequest> = emptyList(),
    val sentRequests: List<SentSquadRequest> = emptyList(),
    val invites: List<RideInvite> = emptyList()
)

@Singleton
class SquadRepository internal constructor(
    private val apiService: ApiService,
    private val securePreferences: SecurePreferences?,
    private val clock: () -> Long
) {
    @Inject
    constructor(
        apiService: ApiService,
        securePreferences: SecurePreferences
    ) : this(apiService, securePreferences, { System.currentTimeMillis() })

    companion object {
        const val REJECTED_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L
        private const val PREF_SENT_PREFIX = "squad_sent_requests_v1_"
    }

    private val _snapshot = MutableStateFlow(SquadSnapshot())
    val snapshot: StateFlow<SquadSnapshot> = _snapshot.asStateFlow()

    fun clearCache() {
        _snapshot.value = SquadSnapshot()
    }

    fun hasCachedDataFor(userId: String): Boolean {
        val current = _snapshot.value
        return current.userId == userId && current.hasLoadedOnce
    }

    suspend fun ensureLoaded(userId: String): Result<SquadSnapshot> {
        if (hasCachedDataFor(userId)) {
            purgeExpiredRejectedInState(userId)
            return Result.success(_snapshot.value)
        }
        return refreshAll(userId)
    }

    suspend fun refreshAll(userId: String): Result<SquadSnapshot> = coroutineScope {
        val now = clock()
        val persistedSent = purgeExpiredRejected(loadPersistedSentRequests(userId), now)

        val friendsDeferred = async { getFriends(userId) }
        val incomingDeferred = async { getIncomingSquadRequests() }
        val sentDeferred = async { getSentSquadRequests() }

        val friendsRes = friendsDeferred.await()
        val incomingRes = incomingDeferred.await()
        val sentRes = sentDeferred.await()

        if (friendsRes.isFailure) {
            return@coroutineScope Result.failure(
                friendsRes.exceptionOrNull() ?: Exception("Failed to load squad")
            )
        }

        val friends = friendsRes.getOrDefault(emptyList())
        val incoming = incomingRes.getOrElse {
            if (_snapshot.value.userId == userId) _snapshot.value.incomingRequests else emptyList()
        }

        val serverSent = sentRes.getOrNull()
        val mergedSent = reconcileSentRequests(
            serverList = serverSent,
            localList = persistedSent,
            acceptedFriends = friends,
            nowMillis = now
        )
        persistSentRequests(userId, mergedSent)

        val updated = SquadSnapshot(
            userId = userId,
            hasLoadedOnce = true,
            lastLoadedAtMillis = now,
            friends = friends,
            incomingRequests = incoming,
            sentRequests = mergedSent,
            invites = if (_snapshot.value.userId == userId) _snapshot.value.invites else emptyList()
        )
        _snapshot.value = updated
        Result.success(updated)
    }

    suspend fun getFriends(userId: String): Result<List<Friend>> {
        return try {
            val response = apiService.getFriendsList(userId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to load squad: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getIncomingSquadRequests(): Result<List<IncomingSquadRequest>> {
        return try {
            val response = apiService.getPendingFriendRequests()
            if (response.isSuccessful) {
                val list = (response.body() ?: emptyList()).filter {
                    it.status.equals("PENDING", ignoreCase = true)
                }
                Result.success(list)
            } else if (response.code() == 404 || response.code() == 405) {
                Result.success(emptyList())
            } else {
                Result.failure(Exception("Failed to load requests (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSentSquadRequests(): Result<List<SentSquadRequest>?> {
        return try {
            val response = apiService.getSentFriendRequests()
            if (response.isSuccessful) {
                val dtos = response.body() ?: emptyList()
                Result.success(dtos.map { it.toDomain(clock()) })
            } else if (response.code() == 404 || response.code() == 405) {
                // Backend does not yet expose /api/friends/sent; fall back to local persisted store
                Result.success(null)
            } else {
                Result.failure(Exception("Failed to load sent requests (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchRider(query: String, currentUserId: String): RiderSearchState {
        val clean = query.removePrefix("@").trim()
        if (clean.isEmpty()) return RiderSearchState.Idle
        return try {
            val response = apiService.searchByHandle(clean)
            val profile = response.body()
            when {
                response.isSuccessful && profile != null -> {
                    val relation = classifyRelation(profile, currentUserId)
                    RiderSearchState.Found(profile, relation)
                }
                response.code() == 404 -> RiderSearchState.NotFound(clean)
                else -> RiderSearchState.Error(parseErrorBody(response.errorBody()?.string(), "Search failed (${response.code()})"))
            }
        } catch (e: Exception) {
            RiderSearchState.Error(e.localizedMessage ?: "Network error while searching")
        }
    }

    fun classifyRelation(profile: ProfileResponse, currentUserId: String): RiderRelation {
        if (profile.id == currentUserId) return RiderRelation.SELF
        val snap = _snapshot.value
        val targetHandle = profile.handle?.removePrefix("@")?.trim().orEmpty()
        val isFriend = snap.friends.any { f ->
            f.id == profile.id ||
                (targetHandle.isNotEmpty() && f.handle.removePrefix("@").equals(targetHandle, ignoreCase = true))
        }
        if (isFriend) return RiderRelation.ALREADY_IN_SQUAD

        val hasIncoming = snap.incomingRequests.any { req ->
            req.requesterId == profile.id ||
                (targetHandle.isNotEmpty() && req.requester?.handle?.removePrefix("@").equals(targetHandle, ignoreCase = true))
        }
        if (hasIncoming) return RiderRelation.INCOMING_REQUEST

        val hasPendingSent = snap.sentRequests.any { req ->
            !req.isRejected && (
                req.addresseeId == profile.id ||
                    (targetHandle.isNotEmpty() && req.handle.removePrefix("@").equals(targetHandle, ignoreCase = true))
                )
        }
        if (hasPendingSent) return RiderRelation.PENDING_SENT

        return RiderRelation.AVAILABLE
    }

    suspend fun sendSquadRequest(
        userId: String,
        handle: String,
        knownProfile: ProfileResponse? = null
    ): Result<String> {
        val cleanHandle = handle.removePrefix("@").trim()
        if (cleanHandle.isEmpty()) return Result.failure(Exception("Handle is required"))
        return try {
            val request = FriendRequest(
                addresseeId = knownProfile?.id,
                handle = cleanHandle
            )
            val response = apiService.sendFriendRequest(request)
            if (response.isSuccessful) {
                val bodyMap = response.body() as? Map<*, *>
                val serverStatus = (bodyMap?.get("status") as? String)?.uppercase() ?: "PENDING"
                val serverId = (bodyMap?.get("id") as? String) ?: UUID.randomUUID().toString()
                val serverAddresseeId = (bodyMap?.get("addresseeId") as? String)
                    ?: knownProfile?.id
                    ?: cleanHandle

                if (serverStatus == "PENDING") {
                    val sent = SentSquadRequest(
                        id = serverId,
                        addresseeId = serverAddresseeId,
                        handle = knownProfile?.handle?.removePrefix("@") ?: cleanHandle,
                        displayName = knownProfile?.displayName,
                        bikeModel = knownProfile?.bikeModel,
                        status = "PENDING",
                        createdAtMillis = clock(),
                        rejectedAtMillis = null
                    )
                    _snapshot.update { state ->
                        val nextSent = state.sentRequests
                            .filterNot { it.addresseeId == sent.addresseeId || it.handle.equals(sent.handle, ignoreCase = true) }
                            .plus(sent)
                        persistSentRequests(userId, nextSent)
                        state.copy(sentRequests = nextSent)
                    }
                } else if (serverStatus == "ACCEPTED") {
                    // Server accepted immediately (e.g. mutual request or auto-accept backend):
                    // silently refresh accepted squad list so the rider appears immediately.
                    val newFriend = knownProfile?.let {
                        Friend(
                            id = it.id,
                            handle = it.handle ?: cleanHandle,
                            displayName = it.displayName,
                            bikeModel = it.bikeModel
                        )
                    }
                    _snapshot.update { state ->
                        val nextSent = state.sentRequests.filterNot {
                            it.addresseeId == serverAddresseeId || it.handle.equals(cleanHandle, ignoreCase = true)
                        }
                        val nextIncoming = state.incomingRequests.filterNot { it.requesterId == serverAddresseeId }
                        val nextFriends = if (newFriend != null && state.friends.none { it.id == newFriend.id }) {
                            state.friends + newFriend
                        } else {
                            state.friends
                        }
                        persistSentRequests(userId, nextSent)
                        state.copy(friends = nextFriends, sentRequests = nextSent, incomingRequests = nextIncoming)
                    }
                    refreshAll(userId)
                }
                Result.success(serverStatus)
            } else {
                val err = parseErrorBody(response.errorBody()?.string(), "Failed to send request (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFriend(userId: String, friendId: String): Result<Unit> {
        val previous = _snapshot.value.friends
        _snapshot.update { state ->
            state.copy(friends = state.friends.filterNot { it.id == friendId })
        }
        return try {
            val response = apiService.removeFriend(friendId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                // Roll back optimistic removal on server failure
                _snapshot.update { state -> state.copy(friends = previous) }
                val err = parseErrorBody(response.errorBody()?.string(), "Failed to remove rider (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            _snapshot.update { state -> state.copy(friends = previous) }
            Result.failure(e)
        }
    }

    suspend fun acceptIncomingRequest(userId: String, request: IncomingSquadRequest): Result<Unit> {
        return try {
            val response = apiService.acceptFriendRequest(AcceptFriendRequestBody(requesterId = request.requesterId))
            if (response.isSuccessful) {
                val acceptedFriend = request.requester
                _snapshot.update { state ->
                    val nextIncoming = state.incomingRequests.filterNot {
                        it.id == request.id || it.requesterId == request.requesterId
                    }
                    val nextFriends = if (acceptedFriend != null && state.friends.none { it.id == acceptedFriend.id }) {
                        state.friends + acceptedFriend
                    } else {
                        state.friends
                    }
                    state.copy(incomingRequests = nextIncoming, friends = nextFriends)
                }
                refreshAll(userId)
                Result.success(Unit)
            } else {
                val err = parseErrorBody(response.errorBody()?.string(), "Failed to accept request (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun declineIncomingRequest(userId: String, request: IncomingSquadRequest): Result<Unit> {
        val previous = _snapshot.value.incomingRequests
        _snapshot.update { state ->
            state.copy(
                incomingRequests = state.incomingRequests.filterNot {
                    it.id == request.id || it.requesterId == request.requesterId
                }
            )
        }
        return try {
            val response = apiService.declineFriendRequest(
                DeclineFriendRequestBody(requesterId = request.requesterId, requestId = request.id)
            )
            if (response.isSuccessful || response.code() == 404 || response.code() == 405) {
                Result.success(Unit)
            } else {
                _snapshot.update { state -> state.copy(incomingRequests = previous) }
                val err = parseErrorBody(response.errorBody()?.string(), "Failed to decline request (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            _snapshot.update { state -> state.copy(incomingRequests = previous) }
            Result.failure(e)
        }
    }

    suspend fun dismissSentRequest(userId: String, requestId: String): Result<Unit> {
        _snapshot.update { state ->
            val next = state.sentRequests.filterNot { it.id == requestId }
            persistSentRequests(userId, next)
            state.copy(sentRequests = next)
        }
        return try {
            val response = apiService.cancelOrDismissFriendRequest(requestId)
            if (response.isSuccessful || response.code() == 404 || response.code() == 405) {
                Result.success(Unit)
            } else {
                Result.success(Unit)
            }
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun clearRejectedRequests(userId: String): Result<Unit> {
        val rejectedIds = _snapshot.value.sentRequests.filter { it.isRejected }.map { it.id }
        _snapshot.update { state ->
            val next = state.sentRequests.filterNot { it.isRejected }
            persistSentRequests(userId, next)
            state.copy(sentRequests = next)
        }
        for (id in rejectedIds) {
            try {
                apiService.cancelOrDismissFriendRequest(id)
            } catch (_: Exception) {
            }
        }
        return Result.success(Unit)
    }

    fun markSentRequestRejected(userId: String, handleOrId: String) {
        val clean = handleOrId.removePrefix("@").trim()
        if (clean.isEmpty()) return
        val now = clock()
        _snapshot.update { state ->
            val next = state.sentRequests.map { req ->
                if (req.addresseeId == clean || req.handle.equals(clean, ignoreCase = true)) {
                    req.copy(status = "REJECTED", rejectedAtMillis = req.rejectedAtMillis ?: now)
                } else {
                    req
                }
            }
            persistSentRequests(userId, next)
            state.copy(sentRequests = next)
        }
    }

    fun purgeExpiredRejectedInState(userId: String) {
        val now = clock()
        _snapshot.update { state ->
            val cleaned = purgeExpiredRejected(state.sentRequests, now)
            if (cleaned.size != state.sentRequests.size) {
                persistSentRequests(userId, cleaned)
                state.copy(sentRequests = cleaned)
            } else {
                state
            }
        }
    }

    internal fun purgeExpiredRejected(
        requests: List<SentSquadRequest>,
        nowMillis: Long
    ): List<SentSquadRequest> {
        return requests.filterNot { req ->
            req.isRejected && (nowMillis - (req.rejectedAtMillis ?: req.createdAtMillis)) >= REJECTED_RETENTION_MS
        }
    }

    private fun reconcileSentRequests(
        serverList: List<SentSquadRequest>?,
        localList: List<SentSquadRequest>,
        acceptedFriends: List<Friend>,
        nowMillis: Long
    ): List<SentSquadRequest> {
        val friendIds = acceptedFriends.map { it.id }.toSet()
        val friendHandles = acceptedFriends.map { it.handle.removePrefix("@").lowercase() }.toSet()

        val source = if (serverList != null) {
            // Merge server truth with local rejected timestamps so 7-day auto-expiry stays accurate
            val localById = localList.associateBy { it.id }
            serverList.map { srv ->
                if (srv.isRejected && srv.rejectedAtMillis == null) {
                    srv.copy(rejectedAtMillis = localById[srv.id]?.rejectedAtMillis ?: nowMillis)
                } else {
                    srv
                }
            }
        } else {
            localList
        }

        val activeOnly = source.filterNot { req ->
            req.addresseeId in friendIds ||
                req.handle.removePrefix("@").lowercase() in friendHandles
        }
        return purgeExpiredRejected(activeOnly, nowMillis)
    }

    private fun loadPersistedSentRequests(userId: String): List<SentSquadRequest> {
        val raw = securePreferences?.getString(PREF_SENT_PREFIX + userId, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val addresseeId = obj.optString("addresseeId").takeIf { it.isNotBlank() } ?: continue
                    val handle = obj.optString("handle").takeIf { it.isNotBlank() } ?: continue
                    add(
                        SentSquadRequest(
                            id = id,
                            addresseeId = addresseeId,
                            handle = handle,
                            displayName = obj.optString("displayName").takeIf { it.isNotBlank() },
                            bikeModel = obj.optString("bikeModel").takeIf { it.isNotBlank() },
                            status = obj.optString("status", "PENDING"),
                            createdAtMillis = obj.optLong("createdAtMillis", clock()),
                            rejectedAtMillis = if (obj.has("rejectedAtMillis") && !obj.isNull("rejectedAtMillis")) {
                                obj.optLong("rejectedAtMillis")
                            } else {
                                null
                            }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistSentRequests(userId: String, list: List<SentSquadRequest>) {
        val prefs = securePreferences ?: return
        try {
            val arr = JSONArray()
            for (req in list) {
                val obj = JSONObject().apply {
                    put("id", req.id)
                    put("addresseeId", req.addresseeId)
                    put("handle", req.handle)
                    if (req.displayName != null) put("displayName", req.displayName)
                    if (req.bikeModel != null) put("bikeModel", req.bikeModel)
                    put("status", req.status)
                    put("createdAtMillis", req.createdAtMillis)
                    if (req.rejectedAtMillis != null) put("rejectedAtMillis", req.rejectedAtMillis)
                }
                arr.put(obj)
            }
            prefs.saveString(PREF_SENT_PREFIX + userId, arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun SentSquadRequestDto.toDomain(nowMillis: Long): SentSquadRequest {
        val created = parseIsoMillis(createdAt) ?: nowMillis
        val rejected = if (status.equals("REJECTED", ignoreCase = true) || status.equals("DECLINED", ignoreCase = true)) {
            parseIsoMillis(updatedAt) ?: created
        } else {
            null
        }
        return SentSquadRequest(
            id = id,
            addresseeId = addresseeId,
            handle = addressee?.handle ?: addresseeId,
            displayName = addressee?.displayName,
            bikeModel = addressee?.bikeModel,
            status = status.uppercase(),
            createdAtMillis = created,
            rejectedAtMillis = rejected
        )
    }

    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorBody(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        return try {
            val json = JSONObject(raw)
            json.optString("error").takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    // Legacy compatibility helpers preserved for existing callers
    suspend fun getPendingInvites(userId: String): Result<List<RideInvite>> {
        return try {
            val response = apiService.getPendingInvites(userId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to load invites: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun addFriend(userId: String, handle: String): Result<Unit> {
        return sendSquadRequest(userId, handle).map { }
    }

    suspend fun respondToInvite(inviteId: String, accept: Boolean): Result<Unit> {
        return try {
            val response = apiService.respondToInvite(
                InviteRespondRequest(inviteId, if (accept) "ACCEPTED" else "DECLINED")
            )
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to respond (${response.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getJoinToken(roomName: String): Result<StartRideResponse> {
        return try {
            val response = apiService.getJoinToken(JoinTokenRequest(roomName))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(
                    Exception(parseErrorBody(response.errorBody()?.string(), "Failed to acquire convoy token (${response.code()})"))
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.data.repository.SquadRepository
import com.ridervoice.data.repository.SquadSyncBus
import com.ridervoice.data.repository.SquadSyncEvent
import com.ridervoice.models.Friend
import com.ridervoice.models.IncomingSquadRequest
import com.ridervoice.models.ProfileResponse
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RiderSearchState
import com.ridervoice.models.SentSquadRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SquadUiState(
    val friends: List<Friend> = emptyList(),
    val incomingRequests: List<IncomingSquadRequest> = emptyList(),
    val sentRequests: List<SentSquadRequest> = emptyList(),
    val invites: List<RideInvite> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val searchState: RiderSearchState = RiderSearchState.Idle,
    val isSendingRequest: Boolean = false,
    val processingRequestIds: Set<String> = emptySet()
)

@HiltViewModel
class SquadViewModel @Inject constructor(
    private val squadRepository: SquadRepository,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository,
    private val squadSyncBus: SquadSyncBus
) : ViewModel() {

    private var activeUserId: String? = null
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(initialStateFromCache())
    val uiState: StateFlow<SquadUiState> = _uiState.asStateFlow()

    companion object {
        private const val RESUME_STALE_MS = 45_000L
        private const val SEARCH_DEBOUNCE_MS = 350L
    }

    init {
        viewModelScope.launch {
            squadRepository.snapshot.collect { snap ->
                _uiState.update { current ->
                    current.copy(
                        friends = snap.friends,
                        incomingRequests = snap.incomingRequests,
                        sentRequests = snap.sentRequests,
                        invites = snap.invites,
                        hasLoadedOnce = snap.hasLoadedOnce,
                        isLoading = if (snap.hasLoadedOnce) false else current.isLoading
                    )
                }
            }
        }

        // Live listener for incoming/accepted/rejected squad push events
        viewModelScope.launch {
            squadSyncBus.events.collect { event ->
                val uid = activeUserId ?: squadRepository.snapshot.value.userId ?: return@collect
                if (event is SquadSyncEvent.RequestRejected && !event.handleOrId.isNullOrBlank()) {
                    squadRepository.markSentRequestRejected(uid, event.handleOrId)
                }
                silentRefresh(uid)
            }
        }
    }

    private fun initialStateFromCache(): SquadUiState {
        val snap = squadRepository.snapshot.value
        return SquadUiState(
            friends = snap.friends,
            incomingRequests = snap.incomingRequests,
            sentRequests = snap.sentRequests,
            invites = snap.invites,
            isLoading = !snap.hasLoadedOnce,
            hasLoadedOnce = snap.hasLoadedOnce
        )
    }

    /**
     * Loads squad data ONLY if not already cached for [userId].
     * Switching tabs back to My Squad renders instantly without a loading spinner or redundant reload.
     */
    fun ensureLoaded(userId: String) {
        activeUserId = userId
        if (squadRepository.hasCachedDataFor(userId)) {
            squadRepository.purgeExpiredRejectedInState(userId)
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = squadRepository.ensureLoaded(userId)
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /**
     * Pull-to-refresh gesture handler: refreshes in background while preserving existing list on screen.
     */
    fun pullToRefresh(userId: String) {
        activeUserId = userId
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
        viewModelScope.launch {
            val result = squadRepository.refreshAll(userId)
            _uiState.update { state ->
                state.copy(
                    isRefreshing = false,
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /**
     * Called on lifecycle ON_RESUME to silently pick up server changes when returning from background,
     * without ever flashing a full-screen loading spinner.
     */
    fun onForegroundResume(userId: String) {
        activeUserId = userId
        val snap = squadRepository.snapshot.value
        if (!snap.hasLoadedOnce || snap.userId != userId) {
            ensureLoaded(userId)
            return
        }
        squadRepository.purgeExpiredRejectedInState(userId)
        val age = System.currentTimeMillis() - snap.lastLoadedAtMillis
        if (age >= RESUME_STALE_MS) {
            silentRefresh(userId)
        }
    }

    private fun silentRefresh(userId: String) {
        viewModelScope.launch {
            squadRepository.refreshAll(userId)
        }
    }

    fun fetchData(userId: String) {
        pullToRefresh(userId)
    }

    fun onSearchQueryChanged(query: String, currentUserId: String, immediate: Boolean = false) {
        searchJob?.cancel()
        val clean = query.removePrefix("@").trim()
        if (clean.length < 2) {
            _uiState.update { it.copy(searchState = RiderSearchState.Idle) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) {
                delay(SEARCH_DEBOUNCE_MS)
            }
            _uiState.update { it.copy(searchState = RiderSearchState.Searching) }
            val result = squadRepository.searchRider(clean, currentUserId)
            _uiState.update { it.copy(searchState = result) }
        }
    }

    fun resetSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchState = RiderSearchState.Idle, isSendingRequest = false) }
    }

    fun sendSquadRequest(userId: String, profile: ProfileResponse, onDone: () -> Unit = {}) {
        val handle = profile.handle?.takeIf { it.isNotBlank() } ?: profile.id
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingRequest = true, errorMessage = null) }
            val result = squadRepository.sendSquadRequest(userId, handle, knownProfile = profile)
            if (result.isSuccess) {
                val updatedRelation = squadRepository.classifyRelation(profile, userId)
                _uiState.update {
                    it.copy(
                        isSendingRequest = false,
                        searchState = RiderSearchState.Found(profile, updatedRelation)
                    )
                }
                onDone()
            } else {
                _uiState.update {
                    it.copy(
                        isSendingRequest = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to send request"
                    )
                }
            }
        }
    }

    fun addFriend(userId: String, friendHandle: String) {
        viewModelScope.launch {
            val cleanHandle = friendHandle.removePrefix("@").trim()
            if (cleanHandle.isEmpty()) return@launch
            _uiState.update { it.copy(isSendingRequest = true, errorMessage = null) }
            val result = squadRepository.sendSquadRequest(userId, cleanHandle)
            _uiState.update {
                it.copy(
                    isSendingRequest = false,
                    errorMessage = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun acceptIncomingRequest(userId: String, request: IncomingSquadRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingRequestIds = it.processingRequestIds + request.id, errorMessage = null) }
            val result = squadRepository.acceptIncomingRequest(userId, request)
            _uiState.update {
                it.copy(
                    processingRequestIds = it.processingRequestIds - request.id,
                    errorMessage = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun acceptIncomingByProfile(userId: String, profile: ProfileResponse, onDone: () -> Unit = {}) {
        val matching = _uiState.value.incomingRequests.firstOrNull {
            it.requesterId == profile.id ||
                (!profile.handle.isNullOrBlank() && it.requester?.handle.equals(profile.handle, ignoreCase = true))
        }
        if (matching != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isSendingRequest = true, errorMessage = null) }
                val res = squadRepository.acceptIncomingRequest(userId, matching)
                val updatedRelation = squadRepository.classifyRelation(profile, userId)
                _uiState.update {
                    it.copy(
                        isSendingRequest = false,
                        searchState = RiderSearchState.Found(profile, updatedRelation),
                        errorMessage = res.exceptionOrNull()?.message
                    )
                }
                if (res.isSuccess) onDone()
            }
        } else {
            sendSquadRequest(userId, profile, onDone)
        }
    }

    fun declineIncomingRequest(userId: String, request: IncomingSquadRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingRequestIds = it.processingRequestIds + request.id, errorMessage = null) }
            val result = squadRepository.declineIncomingRequest(userId, request)
            _uiState.update {
                it.copy(
                    processingRequestIds = it.processingRequestIds - request.id,
                    errorMessage = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun dismissSentRequest(userId: String, requestId: String) {
        viewModelScope.launch {
            squadRepository.dismissSentRequest(userId, requestId)
        }
    }

    fun clearRejectedRequests(userId: String) {
        viewModelScope.launch {
            squadRepository.clearRejectedRequests(userId)
        }
    }

    fun removeFriend(userId: String, friendId: String) {
        viewModelScope.launch {
            val result = squadRepository.removeFriend(userId, friendId)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Failed to remove rider")
                }
            }
        }
    }

    fun removeFriend(friendId: String) {
        val uid = activeUserId ?: squadRepository.snapshot.value.userId ?: ""
        removeFriend(uid, friendId)
    }

    fun acceptInvite(invite: RideInvite, onAcceptSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val result = squadRepository.respondToInvite(invite.id, true)
            if (result.isSuccess) {
                val tokenResult = squadRepository.getJoinToken(invite.room.name)
                val tokenData = tokenResult.getOrNull()
                if (tokenResult.isSuccess && tokenData != null) {
                    activeRideRepository.enterLobby(
                        roomId = tokenData.roomId ?: invite.roomId,
                        name = tokenData.roomName ?: invite.room.name,
                        isHost = false
                    )
                    activeRideRepository.applyTokenPayload(
                        com.ridervoice.models.TokenPayload(
                            token = tokenData.token,
                            livekitUrl = tokenData.livekitUrl,
                            roomId = tokenData.roomId ?: invite.roomId,
                            roomName = tokenData.roomName ?: invite.room.name,
                            diagnosticsEnabled = tokenData.diagnosticsEnabled,
                            testNumber = tokenData.testNumber
                        )
                    )
                    onAcceptSuccess(invite.room.name)
                } else {
                    _uiState.update {
                        it.copy(errorMessage = tokenResult.exceptionOrNull()?.message ?: "Failed to acquire ride audio token")
                    }
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Failed to accept invite")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

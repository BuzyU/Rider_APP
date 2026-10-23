package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.InviteRespondRequest
import com.ridervoice.models.JoinTokenRequest
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RideSession
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InvitesInboxViewModel @Inject constructor(
    private val apiService: ApiService,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository
) : ViewModel() {

    private val _invites = MutableStateFlow<List<RideInvite>>(emptyList())
    val invites: StateFlow<List<RideInvite>> = _invites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadInvites() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = apiService.getPendingInvites(userId)
                if (response.isSuccessful) {
                    _invites.value = response.body() ?: emptyList()
                } else {
                    _error.value = "Failed to load invites (${response.code()})"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to load invites"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun respondToInvite(invite: RideInvite, accept: Boolean, onAcceptSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                if (accept) {
                    val response = apiService.acceptRideInvite(invite.id)
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        activeRideRepository.enterLobby(
                            roomId = body.roomId ?: invite.roomId,
                            name = body.roomName ?: invite.room.name,
                            isHost = false
                        )
                        activeRideRepository.applyTokenPayload(
                            com.ridervoice.models.TokenPayload(
                                token = body.token,
                                livekitUrl = body.livekitUrl,
                                roomId = body.roomId ?: invite.roomId,
                                roomName = body.roomName ?: invite.room.name,
                                diagnosticsEnabled = body.diagnosticsEnabled,
                                testNumber = body.testNumber
                            )
                        )
                        _invites.value = _invites.value.filter { it.id != invite.id }
                        onAcceptSuccess(body.roomName ?: invite.room.name)
                    } else {
                        _error.value = "Failed to accept invite (code ${response.code()}). Please try again."
                    }
                } else {
                    val req = InviteRespondRequest(
                        inviteId = invite.id,
                        response = "DECLINED"
                    )
                    val response = apiService.respondToInvite(req)
                    if (response.isSuccessful) {
                        _invites.value = _invites.value.filter { it.id != invite.id }
                    } else {
                        _error.value = "Failed to decline invite (${response.code()})"
                    }
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Network error while responding to invite"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

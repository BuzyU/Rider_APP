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
    private val apiService: ApiService
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
                val req = InviteRespondRequest(
                    inviteId = invite.id,
                    response = if (accept) "ACCEPTED" else "DECLINED"
                )
                val response = apiService.respondToInvite(req)
                if (response.isSuccessful) {
                    _invites.value = _invites.value.filter { it.id != invite.id }
                    if (accept) {
                        val tokenReq = JoinTokenRequest(invite.room.name)
                        val tokenRes = apiService.getJoinToken(tokenReq)
                        if (tokenRes.isSuccessful && tokenRes.body() != null) {
                            val body = tokenRes.body()!!
                            RideSession.livekitToken = body.token
                            RideSession.livekitUrl = body.livekitUrl.ifBlank { com.ridervoice.utils.Constants.LIVEKIT_URL }
                            RideSession.activeRoomName = invite.room.name
                            RideSession.diagnosticsEnabled = body.diagnosticsEnabled
                            RideSession.testNumber = body.testNumber
                            RideSession.isHost = false
                            onAcceptSuccess(invite.room.name)
                        } else {
                            _error.value = "Invite accepted but failed to connect. Please retry joining from the Home screen."
                        }
                    }
                } else {
                    _error.value = "Failed to respond to invite (${response.code()})"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to respond to invite"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

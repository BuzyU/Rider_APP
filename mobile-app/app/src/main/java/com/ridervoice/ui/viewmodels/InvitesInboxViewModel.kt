package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.InviteRespondRequest
import com.ridervoice.models.RideInvite
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
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun loadInvites() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = apiService.getPendingInvites(userId)
                if (response.isSuccessful) {
                    _invites.value = response.body() ?: emptyList()
                } else {
                    _error.value = "Failed to load invites"
                }
            } catch (e: Exception) {
                _error.value = e.message
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
                        val tokenReq = com.ridervoice.models.JoinTokenRequest(invite.roomId)
                        val tokenRes = apiService.getJoinToken(tokenReq)
                        if (tokenRes.isSuccessful && tokenRes.body() != null) {
                            com.ridervoice.models.RideSession.livekitToken = tokenRes.body()!!.token
                            onAcceptSuccess(invite.roomId)
                        } else {
                            _error.value = "Failed to get join token"
                        }
                    }
                } else {
                    _error.value = "Failed to respond to invite"
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
}

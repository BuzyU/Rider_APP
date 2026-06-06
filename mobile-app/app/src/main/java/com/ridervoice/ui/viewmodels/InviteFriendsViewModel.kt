package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.Friend
import com.ridervoice.models.SendInviteRequest
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InviteFriendsViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun loadFriends() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = apiService.getFriendsList(userId)
                if (response.isSuccessful) {
                    _friends.value = response.body() ?: emptyList()
                } else {
                    _error.value = "Failed to load friends"
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendInvite(roomId: String, friendId: String) {
        viewModelScope.launch {
            try {
                val req = SendInviteRequest(
                    roomId = roomId,
                    inviteeId = friendId
                )
                apiService.sendRideInvite(req)
                // Optionally mark friend as invited in UI state
            } catch (e: Exception) {
                _error.value = "Failed to send invite: ${e.message}"
            }
        }
    }
}

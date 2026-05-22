package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.data.repository.SquadRepository
import com.ridervoice.models.Friend
import com.ridervoice.models.RideInvite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SquadUiState(
    val friends: List<Friend> = emptyList(),
    val invites: List<RideInvite> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

@HiltViewModel
class SquadViewModel @Inject constructor(
    private val squadRepository: SquadRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SquadUiState())
    val uiState: StateFlow<SquadUiState> = _uiState.asStateFlow()

    fun fetchData(userId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val friendsResult = squadRepository.getFriends(userId)
            val invitesResult = squadRepository.getPendingInvites(userId)

            if (friendsResult.isSuccess && invitesResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        friends = friendsResult.getOrDefault(emptyList()),
                        invites = invitesResult.getOrDefault(emptyList())
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = friendsResult.exceptionOrNull()?.message ?: invitesResult.exceptionOrNull()?.message
                    )
                }
            }
        }
    }
}

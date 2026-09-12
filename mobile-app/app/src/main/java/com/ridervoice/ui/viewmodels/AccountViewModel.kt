package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.ridervoice.models.ProfileRequest
import com.ridervoice.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val displayName: String = "",
    val handle: String = "",
    val email: String = "",
    val phone: String = "",
    val bikeModel: String = "",
    val bio: String = "",
    val photoUrl: String? = null,
    val authProvider: String = "Email",
    val createdAt: String? = null
) {

    val initialLetter: String
        get() {
            val candidate = displayName.trim().ifEmpty { handle.trim().removePrefix("@") }
            return candidate.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "R"
        }
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        loadAccountData()
    }

    fun getHighResPhotoUrl(originalUrl: String?): String? {
        if (originalUrl.isNullOrBlank()) return null
        return if (originalUrl.contains(Regex("=[sS]\\d+(-c)?$"))) {
            originalUrl.replace(Regex("=[sS]\\d+(-c)?$"), "=s400-c")
        } else {
            originalUrl
        }
    }

    fun loadAccountData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val firebaseUser = FirebaseAuth.getInstance().currentUser

            var resolvedPhotoUrl: String? = null
            var detectedProvider = "Email"

            if (firebaseUser != null) {

                for (info in firebaseUser.providerData) {
                    if (info.providerId == GoogleAuthProvider.PROVIDER_ID || info.providerId == "google.com") {
                        detectedProvider = "Google"
                        if (info.photoUrl != null) {
                            resolvedPhotoUrl = info.photoUrl.toString()
                        }
                        break
                    } else if (info.providerId == "phone") {
                        detectedProvider = "Phone"
                    }
                }

                if (resolvedPhotoUrl == null && firebaseUser.photoUrl != null) {
                    resolvedPhotoUrl = firebaseUser.photoUrl.toString()
                }

                if (firebaseUser.isAnonymous) {
                    detectedProvider = "Guest"
                }
            }

            resolvedPhotoUrl = getHighResPhotoUrl(resolvedPhotoUrl)

            var handle = ""
            var displayName = firebaseUser?.displayName ?: ""
            var email = firebaseUser?.email ?: ""
            var phone = firebaseUser?.phoneNumber ?: ""
            var bikeModel = ""
            var bio = ""
            var createdAt: String? = null

            try {
                val profileResponse = apiService.getMyProfile()
                if (profileResponse.isSuccessful && profileResponse.body() != null) {
                    val p = profileResponse.body()!!
                    if (!p.displayName.isNullOrBlank()) displayName = p.displayName
                    if (!p.handle.isNullOrBlank()) handle = p.handle
                    if (!p.email.isNullOrBlank() && email.isBlank()) email = p.email
                    if (!p.phone.isNullOrBlank() && phone.isBlank()) phone = p.phone
                    if (!p.bikeModel.isNullOrBlank()) bikeModel = p.bikeModel
                    if (!p.bio.isNullOrBlank()) bio = p.bio
                    createdAt = p.createdAt
                }
            } catch (e: Exception) {

            }

            if (handle.isBlank() && firebaseUser != null) {
                handle = "rider" + firebaseUser.uid.takeLast(6)
            }

            _uiState.value = AccountUiState(
                isLoading = false,
                isSaving = false,
                errorMessage = null,
                displayName = displayName,
                handle = handle,
                email = email.ifBlank { "Not provided" },
                phone = phone.ifBlank { "Not configured" },
                bikeModel = bikeModel.ifBlank { "Not configured" },
                bio = bio.ifBlank { "No bio provided" },
                photoUrl = resolvedPhotoUrl,
                authProvider = detectedProvider,
                createdAt = createdAt
            )
        }
    }

    fun updateProfile(
        newDisplayName: String,
        newHandle: String,
        newPhone: String,
        newBikeModel: String,
        newBio: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            val cleanHandle = newHandle.trim().removePrefix("@")

            try {
                val response = apiService.upsertProfile(
                    ProfileRequest(
                        handle = cleanHandle.ifBlank { null },
                        displayName = newDisplayName.trim().ifBlank { null },
                        bikeModel = newBikeModel.trim().ifBlank { null },
                        bio = newBio.trim().ifBlank { null },
                        phone = newPhone.trim().ifBlank { null }
                    )
                )

                if (response.isSuccessful && response.body() != null) {
                    val updated = response.body()!!
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        successMessage = "Profile successfully calibrated.",
                        displayName = updated.displayName ?: newDisplayName,
                        handle = updated.handle ?: cleanHandle,
                        phone = updated.phone ?: newPhone.ifBlank { "Not configured" },
                        bikeModel = updated.bikeModel ?: newBikeModel.ifBlank { "Not configured" },
                        bio = updated.bio ?: newBio.ifBlank { "No bio provided" }
                    )
                } else if (response.code() == 409) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = "Handle @$cleanHandle is already taken. Please choose another."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = "Unable to update profile. Server returned code ${response.code()}."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Network error: ${e.localizedMessage ?: "Failed to update profile."}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}

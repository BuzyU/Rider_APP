package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.network.ApiService
import com.ridervoice.models.ProfileRequest
import com.ridervoice.security.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    private val securePrefs: com.ridervoice.security.SecurePreferences,
    private val activeRideRepository: com.ridervoice.state.ActiveRideRepository,
    private val liveKitManager: com.ridervoice.network.LiveKitManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _otpSent = MutableStateFlow(false)
    val otpSent: StateFlow<Boolean> = _otpSent.asStateFlow()

    private val _wrongPasswordCount = MutableStateFlow(0)
    val wrongPasswordCount: StateFlow<Int> = _wrongPasswordCount.asStateFlow()

    private val _resetEmailSent = MutableStateFlow(false)
    val resetEmailSent: StateFlow<Boolean> = _resetEmailSent.asStateFlow()

    private var _verificationId: String? = null

    private suspend fun ensureProfile(customHandle: String? = null) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        try {
            com.ridervoice.services.FcmTokenManager.registerCurrentToken(apiService)
            val existing = apiService.getMyProfile()
            if (existing.isSuccessful && existing.body()?.handle != null && customHandle == null) return

            val baseHandle = if (!customHandle.isNullOrBlank()) customHandle else ("rider" + user.uid.takeLast(6).lowercase())
            val resolvedName = user.displayName?.takeIf { it.isNotBlank() }
                ?: if (user.isAnonymous || user.email?.endsWith("@guest.ridervoice.local") == true) "Guest Rider"
                else (user.email?.substringBefore("@") ?: "Rider")

            val res = apiService.upsertProfile(
                ProfileRequest(
                    handle = baseHandle,
                    displayName = resolvedName
                )
            )
            if (!res.isSuccessful && res.code() == 409 && customHandle == null) {
                val fallbackHandle = "rider" + (100000..999999).random()
                apiService.upsertProfile(
                    ProfileRequest(
                        handle = fallbackHandle,
                        displayName = resolvedName
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val error = authRepository.signInAnonymously()
            if (error == null) {
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _errorMessage.value = error
            }

            _isLoading.value = false
        }
    }

    fun signOut() {
        authRepository.signOut()
        com.ridervoice.models.RideSession.clear()
        securePrefs.clearActiveRide()
        activeRideRepository.clear(com.ridervoice.state.ActiveRideRepository.ClearReason.SIGNED_OUT)
        liveKitManager.disconnect()
        com.ridervoice.services.VoiceForegroundService.stop(context)
        _loginSuccess.value = false
        _otpSent.value = false
        _verificationId = null
        _wrongPasswordCount.value = 0
        _resetEmailSent.value = false
    }

    fun handleGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val success = authRepository.signInWithGoogle(idToken)
            if (success) {
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _errorMessage.value = "Google sign-in failed."
            }
            _isLoading.value = false
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val error = authRepository.signInWithEmail(email, password)
            if (error == null) {
                _wrongPasswordCount.value = 0
                ensureProfile()
                _loginSuccess.value = true
            } else {

                if (error.contains("Wrong password", ignoreCase = true) ||
                    error.contains("password", ignoreCase = true)
                ) {
                    _wrongPasswordCount.value += 1
                }
                _errorMessage.value = error
            }
            _isLoading.value = false
        }
    }

    fun createAccountWithEmail(email: String, password: String, handle: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val error = authRepository.createAccountWithEmail(email, password)
            if (error == null) {
                ensureProfile(handle)
                _loginSuccess.value = true
            } else {
                _errorMessage.value = error
            }
            _isLoading.value = false
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val error = authRepository.sendPasswordResetEmail(email)
            if (error == null) {
                _resetEmailSent.value = true
            } else {
                _errorMessage.value = error
            }
            _isLoading.value = false
        }
    }

    fun clearResetEmailSent() {
        _resetEmailSent.value = false
    }

    fun onOtpSent(verificationId: String) {
        _verificationId = verificationId
        _otpSent.value = true
        _isLoading.value = false
    }

    fun handlePhoneCredential(credential: com.google.firebase.auth.PhoneAuthCredential) {
        viewModelScope.launch {
            _isLoading.value = true
            val success = authRepository.signInWithPhoneAuthCredential(credential)
            if (success) {
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _errorMessage.value = "Invalid OTP or sign-in failed."
                _isLoading.value = false
            }
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun getVerificationId(): String? = _verificationId
}

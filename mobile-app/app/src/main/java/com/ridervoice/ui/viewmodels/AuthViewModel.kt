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
    private val securePrefs: com.ridervoice.security.SecurePreferences
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _otpSent = MutableStateFlow(false)
    val otpSent: StateFlow<Boolean> = _otpSent.asStateFlow()

    // Tracks consecutive wrong-password attempts to reveal "Forgot password?"
    private val _wrongPasswordCount = MutableStateFlow(0)
    val wrongPasswordCount: StateFlow<Int> = _wrongPasswordCount.asStateFlow()

    // Signals a successful password reset email dispatch
    private val _resetEmailSent = MutableStateFlow(false)
    val resetEmailSent: StateFlow<Boolean> = _resetEmailSent.asStateFlow()

    private var _verificationId: String? = null

    /**
     * Auto-provisions a unique handle/profile row on first login so features that
     * depend on `handle` (add-friend-by-handle, search) work immediately.
     * Non-fatal: a failure here must never block the user from reaching the app.
     */
    private suspend fun ensureProfile(customHandle: String? = null) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        try {
            val existing = apiService.getMyProfile()
            if (existing.isSuccessful && existing.body()?.handle != null && customHandle == null) return // already provisioned

            val autoHandle = if (!customHandle.isNullOrBlank()) customHandle else ("rider" + user.uid.takeLast(6))
            apiService.upsertProfile(
                ProfileRequest(
                    handle = autoHandle,
                    displayName = user.displayName ?: user.email?.substringBefore("@") ?: "Rider"
                )
            )
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

            val success = authRepository.signInAnonymously()
            if (success) {
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _errorMessage.value = "Guest sign-in failed. Check your network or Firebase setup."
            }

            _isLoading.value = false
        }
    }

    fun signOut() {
        authRepository.signOut()
        com.ridervoice.models.RideSession.clear()
        securePrefs.clearActiveRide()
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

    /** Sign in with email + password. Increments wrong-password counter for Forgot Password reveal. */
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
                // Count wrong-password attempts specifically to reveal "Forgot password?"
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

    /** Register a new account with email + password. */
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

    /** Send password-reset email. */
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
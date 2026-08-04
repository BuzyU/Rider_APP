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
    private val apiService: ApiService
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _otpSent = MutableStateFlow(false)
    val otpSent: StateFlow<Boolean> = _otpSent.asStateFlow()

    // Track failed email/password attempts to surface "Forgot password"
    private val _failedAttempts = MutableStateFlow(0)
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private var _verificationId: String? = null

    /**
     * Auto-provisions a unique handle/profile row on first login so features that
     * depend on `handle` (add-friend-by-handle, search) work immediately.
     * Non-fatal: a failure here must never block the user from reaching the app.
     */
    private suspend fun ensureProfile() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        try {
            val existing = apiService.getMyProfile()
            if (existing.isSuccessful && existing.body()?.handle != null) return // already provisioned

            val autoHandle = "rider" + user.uid.takeLast(6)
            apiService.upsertProfile(
                ProfileRequest(
                    handle = autoHandle,
                    displayName = user.displayName ?: "Rider"
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        _loginSuccess.value = false
        _otpSent.value = false
        _verificationId = null
        _failedAttempts.value = 0
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

    // New: Email/password sign-in
    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val success = authRepository.signInWithEmail(email.trim(), password)
            if (success) {
                _failedAttempts.value = 0
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _failedAttempts.value = _failedAttempts.value + 1
                _errorMessage.value = "Email sign-in failed. Check credentials or network."
            }
            _isLoading.value = false
        }
    }

    // New: Register via email/password
    fun registerWithEmail(email: String, password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val success = authRepository.registerWithEmail(email.trim(), password)
            if (success) {
                ensureProfile()
                _loginSuccess.value = true
            } else {
                _errorMessage.value = "Registration failed."
            }
            _isLoading.value = false
            onResult(success)
        }
    }

    // New: Send password reset
    fun sendPasswordReset(email: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val success = authRepository.sendPasswordReset(email.trim())
            if (!success) {
                _errorMessage.value = "Failed to send password reset."
            }
            _isLoading.value = false
            onResult(success)
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
    
    fun getVerificationId(): String? = _verificationId
}

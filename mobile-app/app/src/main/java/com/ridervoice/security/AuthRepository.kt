package com.ridervoice.security

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor() {

    private val auth = FirebaseAuth.getInstance()

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }

    suspend fun getAuthToken(): String? {
        val user = auth.currentUser ?: return null
        return try {
            val result = user.getIdToken(false).await()
            result.token
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun signInAnonymously(): Boolean {
        return try {
            auth.signInAnonymously().await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun signOut() {
        com.ridervoice.di.AppModule.AuthTokenCache.clear()
        auth.signOut()
    }

    suspend fun signInWithGoogle(idToken: String): Boolean {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun signInWithPhoneAuthCredential(credential: PhoneAuthCredential): Boolean {
        return try {
            auth.signInWithCredential(credential).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun signInWithEmail(email: String, password: String): String? {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            null
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            "Wrong password. Please try again."
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            "No account found with this email."
        } catch (e: Exception) {
            e.localizedMessage ?: "Sign-in failed."
        }
    }

    suspend fun createAccountWithEmail(email: String, password: String): String? {
        return try {
            auth.createUserWithEmailAndPassword(email, password).await()
            null
        } catch (e: com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
            "Password must be at least 6 characters."
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            "An account with this email already exists."
        } catch (e: Exception) {
            e.localizedMessage ?: "Registration failed."
        }
    }

    suspend fun sendPasswordResetEmail(email: String): String? {
        return try {
            auth.sendPasswordResetEmail(email).await()
            null
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            "No account found with this email."
        } catch (e: Exception) {
            e.localizedMessage ?: "Failed to send reset email."
        }
    }
}

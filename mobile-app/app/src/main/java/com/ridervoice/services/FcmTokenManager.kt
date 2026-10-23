package com.ridervoice.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.ridervoice.models.FcmTokenRequest
import com.ridervoice.network.ApiService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object FcmTokenManager {
    private const val TAG = "FcmTokenManager"

    suspend fun registerCurrentToken(apiService: ApiService) {
        try {
            val token = fetchToken() ?: return
            if (token.isNotBlank()) {
                val res = apiService.updateFcmToken(FcmTokenRequest(token = token, platform = "android"))
                if (res.isSuccessful) {
                    Log.d(TAG, "FCM token registered successfully at login/startup")
                } else {
                    Log.w(TAG, "FCM token registration returned ${res.code()}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register current FCM token: ${e.message}")
        }
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (continuation.isActive) {
                        continuation.resume(token)
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Error retrieving FCM token: ${error.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseMessaging.getInstance() unavailable: ${e.message}")
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}

package com.ridervoice.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Minimal Firebase Cloud Messaging handler.
 * Replaces the deleted TacticalMessagingService (DEAD-10).
 * Expand when push notification features are needed.
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        // TODO: send token to backend via ApiService.updateDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message from: ${message.from}, data: ${message.data}")
        // TODO: handle ping/hazard/invite push notifications
    }

    companion object {
        private const val TAG = "FcmService"
    }
}

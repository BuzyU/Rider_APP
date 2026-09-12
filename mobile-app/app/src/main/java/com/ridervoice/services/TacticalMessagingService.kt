package com.ridervoice.services

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ridervoice.ui.screens.EmergencyAlertActivity
import com.ridervoice.ui.screens.RideInviteActivity
import com.ridervoice.network.ApiService
import com.ridervoice.models.FcmTokenRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TacticalMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TacticalFCM"
    }

    @Inject
    lateinit var apiService: ApiService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed — uploading to backend")
        uploadTokenToBackend(token)
    }

    private fun uploadTokenToBackend(token: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {

            Log.w(TAG, "No user signed in — deferring token upload")
            return
        }

        serviceScope.launch {
            try {
                val response = apiService.updateFcmToken(
                    FcmTokenRequest(
                        userId   = user.uid,
                        token    = token,
                        platform = "android"
                    )
                )
                if (response.isSuccessful) {
                    Log.i(TAG, "✅ FCM token uploaded for ${user.uid}")
                } else {
                    Log.e(TAG, "Token upload failed: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token upload exception: ${e.message}")

            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM received — type: ${message.data["type"]}")

        val type      = message.data["type"]      ?: return
        val channelId = message.data["channelId"] ?: "CHANNEL_CONVOY"

        when (type) {
            "RIDE_INVITE" -> handleRideInvite(message.data, channelId)
            "EMERGENCY"   -> handleEmergency(message.data, channelId)
            else          -> Log.w(TAG, "Unknown FCM type: $type")
        }
    }

    private fun handleRideInvite(data: Map<String, String>, channelId: String) {
        val inviter  = data["inviterHandle"] ?: "A rider"
        val roomName = data["roomName"]      ?: "a convoy"

        val fullScreenIntent = Intent(this, RideInviteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("inviterHandle", inviter)
            putExtra("roomName", roomName)
        }
        val fullScreenPI = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tactical Invite")
            .setContentText("$inviter invited you to $roomName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPI, true)
            .setAutoCancel(true)
            .build()

        showNotification(notification)
    }

    private fun handleEmergency(data: Map<String, String>, channelId: String) {
        val alertType = data["alertType"] ?: "SOS"

        val fullScreenIntent = Intent(this, EmergencyAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("alertType", alertType)
        }
        val fullScreenPI = PendingIntent.getActivity(
            this, 1, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("EMERGENCY ALERT")
            .setContentText("CRITICAL: $alertType detected in squad")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPI, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        showNotification(notification)
    }

    private fun showNotification(notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this)
                .notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission missing — notification suppressed")
        }
    }
}

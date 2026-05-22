package com.ridervoice.services

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ridervoice.R
import com.ridervoice.ui.screens.EmergencyAlertActivity
import com.ridervoice.ui.screens.RideInviteActivity

class TacticalMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("TacticalFCM", "New FCM Token: $token")
        // TODO: Send to backend /api/users/fcm-token
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        Log.d("TacticalFCM", "Received FCM Payload: ${message.data}")

        val type = message.data["type"]
        val channelId = message.data["channelId"] ?: "CHANNEL_CONVOY"

        when (type) {
            "RIDE_INVITE" -> handleRideInvite(message.data, channelId)
            "EMERGENCY" -> handleEmergency(message.data, channelId)
            else -> Log.w("TacticalFCM", "Unknown notification type: $type")
        }
    }

    private fun handleRideInvite(data: Map<String, String>, channelId: String) {
        val inviter = data["inviterHandle"] ?: "A rider"
        val roomName = data["roomName"] ?: "a convoy"

        val fullScreenIntent = Intent(this, RideInviteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("inviterHandle", inviter)
            putExtra("roomName", roomName)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tactical Invite")
            .setContentText("$inviter invited you to $roomName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true) // Wakes screen!
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e("TacticalFCM", "Missing POST_NOTIFICATIONS permission", e)
        }
    }

    private fun handleEmergency(data: Map<String, String>, channelId: String) {
        val alertType = data["alertType"] ?: "SOS"
        
        val fullScreenIntent = Intent(this, EmergencyAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("alertType", alertType)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 1, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("EMERGENCY ALERT")
            .setContentText("CRITICAL: $alertType detected in squad.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e("TacticalFCM", "Missing POST_NOTIFICATIONS permission", e)
        }
    }
}

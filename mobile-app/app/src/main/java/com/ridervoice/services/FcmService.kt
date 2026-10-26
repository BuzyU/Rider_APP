package com.ridervoice.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ridervoice.R
import com.ridervoice.network.ApiService
import com.ridervoice.ui.screens.EmergencyAlertActivity
import com.ridervoice.ui.screens.RideInviteActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        scope.launch {
            try {
                apiService.updateFcmToken(com.ridervoice.models.FcmTokenRequest(token = token, platform = "android"))
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering FCM token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) return
        val type = data["type"] ?: return

        when (type) {
            "RIDE_INVITE" -> handleRideInvite(data)
            "INVITE_ACCEPTED" -> handleInviteAccepted(data)
            "EMERGENCY" -> handleEmergency(data)
            else -> Log.d(TAG, "Unhandled FCM message type: $type")
        }
    }

    private fun handleRideInvite(data: Map<String, String>) {
        val inviter = data["senderCallsign"] ?: data["inviterHandle"] ?: "A rider"
        val rideName = data["convoyName"] ?: data["roomName"] ?: "Ride"
        val inviteId = data["inviteId"] ?: ""

        val intent = Intent(this, RideInviteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("inviterHandle", inviter)
            putExtra("roomName", rideName)
            putExtra("inviteId", inviteId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            inviteId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "CHANNEL_CONVOY")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_ride_invite_title))
            .setContentText(getString(R.string.notif_ride_invite_desc, inviter, rideName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(inviteId.hashCode(), notification)
    }

    private fun handleInviteAccepted(data: Map<String, String>) {
        val riderName = data["senderCallsign"] ?: data["riderName"] ?: "A rider"
        val rideName = data["convoyName"] ?: data["roomName"] ?: "Ride"

        val notification = NotificationCompat.Builder(this, "CHANNEL_CONVOY")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_rider_joined_title))
            .setContentText(getString(R.string.notif_rider_joined_desc, riderName, rideName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((riderName + rideName).hashCode(), notification)
    }

    private fun handleEmergency(data: Map<String, String>) {
        val alertType = data["alertType"] ?: "EMERGENCY SOS"
        val roomName = data["roomName"] ?: data["convoyName"] ?: "Ride"
        val sender = data["senderCallsign"] ?: data["senderName"] ?: "A rider"
        val alertId = data["alertId"] ?: ""

        val fullScreenIntent = Intent(this, EmergencyAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alertType", alertType)
            putExtra("roomName", roomName)
            putExtra("senderCallsign", sender)
            putExtra("alertId", alertId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alertId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(this, "CHANNEL_EMERGENCY")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.notif_emergency_title, alertType))
            .setContentText(getString(R.string.notif_emergency_desc, sender, roomName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.notif_open_alert), pendingIntent)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(91100 + alertId.hashCode() % 1000, builder.build())
    }

    companion object {
        private const val TAG = "FcmService"
    }
}

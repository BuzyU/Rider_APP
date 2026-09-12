package com.ridervoice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val emergencyChannel = NotificationChannel(
                "CHANNEL_EMERGENCY",
                "Emergency & SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical crash and SOS alerts from your squad."
                setBypassDnd(true)
            }

            val convoyChannel = NotificationChannel(
                "CHANNEL_CONVOY",
                "Convoy Invites & Drops",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tactical ride invites and unexpected disconnects."
            }

            val squadChannel = NotificationChannel(
                "CHANNEL_SQUAD",
                "Squad Activity",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Friend requests and squad presence."
            }

            val systemChannel = NotificationChannel(
                "CHANNEL_SYSTEM",
                "System Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service persistence indicators."
            }

            notificationManager.createNotificationChannels(
                listOf(emergencyChannel, convoyChannel, squadChannel, systemChannel)
            )
        }
    }
}

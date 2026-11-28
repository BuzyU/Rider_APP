package com.ridervoice.mesh.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ridervoice.R
import com.ridervoice.mesh.MeshConfig

enum class MeshStatusEvent {
    STARTING_OFFLINE,
    OFFLINE_ACTIVE,
    STARTING_ONLINE,
    ONLINE_RECOVERED,
    WIFI_OFF,
    LINK_FAILED
}

interface StatusNotificationPort {
    fun notify(title: String, body: String)
}

interface HapticPort {
    fun pulse(timingsMs: LongArray)
}

class AndroidStatusNotificationPort(
    context: Context,
    private val channelNameProvider: () -> String = {
        runCatching { context.getString(R.string.mesh_status_notif_channel_name) }
            .getOrDefault("Convoy Status Alerts")
    }
) : StatusNotificationPort {
    private val appContext = context.applicationContext ?: context
    @Volatile private var channelCreated = false

    private fun ensureChannel() {
        if (channelCreated) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            MeshConfig.MESH_STATUS_CHANNEL_ID,
            channelNameProvider(),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)
        channelCreated = true
    }

    @SuppressLint("MissingPermission")
    override fun notify(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, MeshConfig.MESH_STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(MeshConfig.MESH_STATUS_NOTIF_ID, notification)
        }
    }
}

class AndroidHapticPort(context: Context) : HapticPort {
    private val appContext = context.applicationContext ?: context

    @SuppressLint("MissingPermission")
    override fun pulse(timingsMs: LongArray) {
        if (timingsMs.isEmpty()) return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(timingsMs, -1))
        }
    }
}

class MeshStatusNotifier(
    context: Context,
    private val audioCuePlayer: MeshAudioCuePlayer = NoOpMeshAudioCuePlayer,
    private val notificationPort: StatusNotificationPort = AndroidStatusNotificationPort(context),
    private val hapticPort: HapticPort = AndroidHapticPort(context),
    private val stringResolver: (Int, Array<out Any>) -> String = { resId, args ->
        val appCtx = context.applicationContext ?: context
        if (args.isEmpty()) appCtx.getString(resId) else appCtx.getString(resId, *args)
    }
) {
    fun notify(
        event: MeshStatusEvent,
        isDeafened: Boolean = false,
        attempts: Int = 0
    ) {
        val body = when (event) {
            MeshStatusEvent.STARTING_OFFLINE -> stringResolver(R.string.mesh_convoy_starting_offline, emptyArray())
            MeshStatusEvent.OFFLINE_ACTIVE -> stringResolver(R.string.mesh_convoy_offline_active, emptyArray())
            MeshStatusEvent.STARTING_ONLINE -> stringResolver(R.string.mesh_convoy_starting_online, emptyArray())
            MeshStatusEvent.ONLINE_RECOVERED -> stringResolver(R.string.mesh_convoy_online_active, emptyArray())
            MeshStatusEvent.WIFI_OFF -> stringResolver(R.string.mesh_convoy_wifi_off, emptyArray())
            MeshStatusEvent.LINK_FAILED -> stringResolver(R.string.mesh_convoy_link_failed, arrayOf(attempts))
        }
        val title = stringResolver(R.string.mesh_status_notif_channel_name, emptyArray())
        notificationPort.notify(title, body)

        when (event) {
            MeshStatusEvent.WIFI_OFF -> hapticPort.pulse(longArrayOf(0L, 50L, 60L, 50L, 60L, 50L))
            MeshStatusEvent.LINK_FAILED -> hapticPort.pulse(longArrayOf(0L, 60L, 80L, 60L))
            MeshStatusEvent.OFFLINE_ACTIVE, MeshStatusEvent.ONLINE_RECOVERED -> hapticPort.pulse(longArrayOf(0L, 60L))
            MeshStatusEvent.STARTING_OFFLINE, MeshStatusEvent.STARTING_ONLINE -> Unit
        }

        audioCuePlayer.playCue(event, body, isDeafened)
    }

    fun release() {
        audioCuePlayer.release()
    }
}

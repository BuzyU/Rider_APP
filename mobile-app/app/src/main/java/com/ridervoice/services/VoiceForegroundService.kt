package com.ridervoice.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import com.ridervoice.MainActivity
import com.ridervoice.R
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VoiceForegroundService : Service() {

    @Inject
    lateinit var liveKitManager: LiveKitManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val channelId = "tactical_voice_channel"
    private val notificationId = 1
    
    // We only hold a wake lock if absolutely necessary, but we rely mostly on foreground status.
    private var wakeLock: PowerManager.WakeLock? = null

    // State for notification
    private var currentConvoyState: String = "Connecting..."
    private var currentActiveSpeaker: String = "Nobody"
    private var isMuted: Boolean = false

    // Debouncing mechanism
    private var updateJob: Job? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        
        // Use weakest possible wake lock strategy: rely primarily on foreground service.
        // We only grab a partial wake lock if telemetry strictly requires it, but for now we trust the OS.

        createNotificationChannel()
        startForeground(notificationId, buildTacticalNotification())

        // Observe LiveKit connection state
        serviceScope.launch {
            liveKitManager.connectionState.collectLatest { state ->
                currentConvoyState = when (state) {
                    ConnectionState.CONNECTED -> "Convoy LIVE"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.FAILED -> "Connection Failed"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                }
                debouncedUpdateNotification()
            }
        }

        // Mocking an active speaker flow (in a real app, this comes from LiveKitManager)
        serviceScope.launch {
            // Suppose liveKitManager.activeSpeaker is a StateFlow<String?>
            // liveKitManager.activeSpeaker.collectLatest { speaker ->
            //     currentActiveSpeaker = speaker ?: "Nobody"
            //     debouncedUpdateNotification()
            // }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SERVICE" -> {
                liveKitManager.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_MUTE" -> {
                isMuted = !isMuted
                // liveKitManager.setMicrophoneMuted(isMuted)
                updateNotificationImmediate()
            }
            "ACTION_PING" -> {
                // Send regroup ping to squad
            }
            "ACTION_HAZARD" -> {
                // Drop hazard pin at current telemetry location
            }
        }
        
        return START_REDELIVER_INTENT
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Tactical Convoy HUD",
                NotificationManager.IMPORTANCE_LOW // Low importance avoids constant popping, keeping it stable
            ).apply {
                description = "Convoy communication and telemetry overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun debouncedUpdateNotification() {
        if (updateJob?.isActive == true) return
        updateJob = serviceScope.launch {
            delay(500) // Rate-limit updates to max twice per second
            updateNotificationImmediate()
        }
    }

    private fun updateNotificationImmediate() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildTacticalNotification())
    }

    private fun buildTacticalNotification(): Notification {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Custom Layouts
        val collapsedViews = RemoteViews(packageName, R.layout.notification_tactical_collapsed)
        val expandedViews = RemoteViews(packageName, R.layout.notification_tactical_expanded)

        // Update Text
        collapsedViews.setTextViewText(R.id.text_convoy_status, currentConvoyState)
        collapsedViews.setTextViewText(R.id.text_active_speaker, "Active: $currentActiveSpeaker")
        
        expandedViews.setTextViewText(R.id.text_convoy_status_expanded, currentConvoyState)
        expandedViews.setTextViewText(R.id.text_active_speaker_expanded, "Active Speaker: $currentActiveSpeaker")

        // Update Icons (Mute state)
        val muteIcon = if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        collapsedViews.setImageViewResource(R.id.btn_mute, muteIcon)
        expandedViews.setImageViewResource(R.id.btn_mute_expanded, muteIcon)

        // Intents for Actions
        val muteIntent = PendingIntent.getService(this, 1, Intent(this, VoiceForegroundService::class.java).apply { action = "ACTION_MUTE" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pingIntent = PendingIntent.getService(this, 2, Intent(this, VoiceForegroundService::class.java).apply { action = "ACTION_PING" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hazardIntent = PendingIntent.getService(this, 3, Intent(this, VoiceForegroundService::class.java).apply { action = "ACTION_HAZARD" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        collapsedViews.setOnClickPendingIntent(R.id.btn_mute, muteIntent)
        
        expandedViews.setOnClickPendingIntent(R.id.btn_mute_expanded, muteIntent)
        expandedViews.setOnClickPendingIntent(R.id.btn_ping, pingIntent)
        expandedViews.setOnClickPendingIntent(R.id.btn_hazard, hazardIntent)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Prevents vibration/sound on every update
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        liveKitManager.disconnect()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

package com.ridervoice.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import com.ridervoice.MainActivity
import com.ridervoice.R
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VoiceForegroundService : Service() {

    @Inject
    lateinit var liveKitManager: LiveKitManager

    @Inject
    lateinit var securePreferences: SecurePreferences

    @Inject
    lateinit var networkDiagnosticsCollector: com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector

    @Inject
    lateinit var activeRideRepository: ActiveRideRepository

    @Inject
    lateinit var locationService: LocationService

    @Inject
    lateinit var rideRecorder: RideRecorder

    @Inject
    lateinit var thermalManager: ThermalManager

    @Inject
    lateinit var serviceWatchdog: ServiceWatchdog

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val channelId = "tactical_voice_channel"
    private val notificationId = 1

    private var wakeLock: PowerManager.WakeLock? = null

    private var currentConvoyState: String = "Connecting..."
    private var currentActiveSpeaker: String = "Nobody"
    private var isMuted: Boolean = false

    private var updateJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        try {
            createNotificationChannel()
            val mask = getForegroundServiceTypeMask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mask != 0) {
                startForeground(notificationId, buildTacticalNotification(), mask)
            } else {
                startForeground(notificationId, buildTacticalNotification())
            }
        } catch (se: SecurityException) {
            android.util.Log.e("VoiceForegroundService", "startForeground SecurityException: ${se.message}")
            try {
                startForeground(notificationId, buildTacticalNotification())
            } catch (_: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("VoiceForegroundService", "startForeground failed", e)
        }

        // Manage connection state updates and wake lock
        serviceScope.launch {
            liveKitManager.connectionState.collectLatest { state ->
                currentConvoyState = when (state) {
                    ConnectionState.CONNECTED -> "Convoy LIVE"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.FAILED -> "Connection Failed"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                }
                updateWakeLock(state)
                debouncedUpdateNotification()
            }
        }

        serviceScope.launch {
            liveKitManager.activeSpeaker.collectLatest { speaker ->
                currentActiveSpeaker = speaker ?: "Nobody"
                debouncedUpdateNotification()
            }
        }

        serviceScope.launch {
            liveKitManager.convoyEnded.collectLatest { ended ->
                if (ended) {
                    stopSelf()
                }
            }
        }

        // Location & Sensor Lifecycle: ServiceScope owns tracking while ride is LIVE
        serviceScope.launch {
            activeRideRepository.state.collectLatest { ride ->
                if (ride != null && ride.phase == ActiveRideRepository.Phase.LIVE) {
                    locationService.startTracking()
                    rideRecorder.startRecording(ride.roomId)
                    thermalManager.startMonitoring(serviceScope)
                    serviceWatchdog.startMonitoring(serviceScope)
                } else {
                    locationService.stopTracking()
                    rideRecorder.stopRecording()
                    thermalManager.stopMonitoring()
                    serviceWatchdog.stopMonitoring()
                }
            }
        }

        // Network Diagnostics Collector gating
        serviceScope.launch {
            combine(
                securePreferences.shareDiagnosticsWhenHostEnabled,
                liveKitManager.connectionState,
                activeRideRepository.state
            ) { riderShareWhenHostEnabled, connState, rideState ->
                val isConnected = connState == ConnectionState.CONNECTED
                val rideEnabled = rideState?.diagnosticsEnabled ?: false
                val isHost = rideState?.isHost ?: false
                val isOptedOut = if (isHost) false else !riderShareWhenHostEnabled
                val shouldRun = com.ridervoice.diagnostics.DiagnosticsGating.shouldStartCollector(
                    isConnected = isConnected,
                    diagnosticsEnabled = rideEnabled,
                    isRiderOptedOut = isOptedOut
                )
                Pair(shouldRun, rideState?.displayName ?: "Ride")
            }.collectLatest { (shouldRun, convoyId) ->
                if (shouldRun) {
                    networkDiagnosticsCollector.startSession(convoyId)
                } else {
                    networkDiagnosticsCollector.stopSession()
                }
            }
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
                isMuted = !liveKitManager.isUserMuted.value
                liveKitManager.setUserMuted(isMuted)
                updateNotificationImmediate()
            }
        }

        // Process death recovery: check if a LIVE ride exists
        if (intent == null) {
            val active = activeRideRepository.state.value
            if (active == null || active.phase != ActiveRideRepository.Phase.LIVE) {
                serviceScope.launch {
                    delay(5000L)
                    stopSelf()
                }
            } else {
                serviceScope.launch {
                    activeRideRepository.ensureFreshToken()
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun updateWakeLock(state: ConnectionState) {
        val shouldHold = state == ConnectionState.CONNECTED || state == ConnectionState.RECONNECTING
        if (shouldHold) {
            if (wakeLock == null) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RiderVoice:VoiceAudioLock").apply {
                        setReferenceCounted(false)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoiceForegroundService", "WakeLock creation failed", e)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(3 * 3600 * 1000L) // 3h renewal window
                    com.ridervoice.utils.RvLog.d("BAT", "WakeLock acquired for 3h")
                }
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    com.ridervoice.utils.RvLog.d("BAT", "WakeLock released")
                }
            }
        }
    }

    private fun getForegroundServiceTypeMask(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var mask = 0
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
             checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
        return if (mask == 0) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else mask
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Tactical Convoy HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Convoy communication and telemetry overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun debouncedUpdateNotification() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            delay(300)
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

        val collapsedViews = RemoteViews(packageName, R.layout.notification_tactical_collapsed)
        val expandedViews = RemoteViews(packageName, R.layout.notification_tactical_expanded)

        collapsedViews.setTextViewText(R.id.text_convoy_status, currentConvoyState)
        collapsedViews.setTextViewText(R.id.text_active_speaker, "Active: $currentActiveSpeaker")

        expandedViews.setTextViewText(R.id.text_convoy_status_expanded, currentConvoyState)
        expandedViews.setTextViewText(R.id.text_active_speaker_expanded, "Active Speaker: $currentActiveSpeaker")

        val muteIcon = if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_btn_speak_now
        collapsedViews.setImageViewResource(R.id.btn_mute, muteIcon)
        expandedViews.setImageViewResource(R.id.btn_mute_expanded, muteIcon)

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
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        locationService.stopTracking()
        rideRecorder.stopRecording()
        thermalManager.stopMonitoring()
        serviceWatchdog.stopMonitoring()
        networkDiagnosticsCollector.stopSession()
        liveKitManager.disconnect()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }
}

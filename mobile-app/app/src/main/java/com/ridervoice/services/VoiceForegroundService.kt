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
open class VoiceForegroundService : Service() {

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

    internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
    internal var appVisibilityProvider: () -> Boolean = { isAppVisible() }
    internal var permissionChecker: (String) -> Int = { perm ->
        androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
    }
    internal var stopSelfAction: () -> Unit = { stopSelf() }

    private fun isAppVisible(): Boolean {
        return try {
            val appProcessInfo = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                appProcessInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (_: Exception) {
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val hasMic = permissionChecker(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            android.util.Log.w("VoiceForegroundService", "RECORD_AUDIO missing on onCreate; terminating")
            stopSelfAction()
            return
        }

        try {
            createNotificationChannel()
            val mask = getForegroundServiceTypeMask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mask != 0) {
                startForeground(notificationId, buildTacticalNotification(), mask)
            } else {
                startForeground(notificationId, buildTacticalNotification())
            }
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError(null)
            }
        } catch (se: SecurityException) {
            android.util.Log.e("VoiceForegroundService", "startForeground SecurityException: ${se.message}")
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError("voice stopped, reopen the app")
            }
            stopSelfAction()
            return
        } catch (e: Exception) {
            android.util.Log.e("VoiceForegroundService", "startForeground failed", e)
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError("voice stopped, reopen the app")
            }
            stopSelfAction()
            return
        }

        // Manage connection state updates and wake lock
        serviceScope.launch {
            liveKitManager.connectionState.collectLatest { state ->
                currentConvoyState = when (state) {
                    ConnectionState.CONNECTED -> getString(R.string.notif_convoy_live)
                    ConnectionState.CONNECTING -> getString(R.string.notif_convoy_connecting)
                    ConnectionState.RECONNECTING -> getString(R.string.notif_convoy_reconnecting)
                    ConnectionState.FAILED -> getString(R.string.notif_convoy_failed)
                    ConnectionState.DISCONNECTED -> getString(R.string.notif_convoy_disconnected)
                }
                updateWakeLock(state)
                debouncedUpdateNotification()
            }
        }

        serviceScope.launch {
            liveKitManager.activeSpeaker.collectLatest { speaker ->
                currentActiveSpeaker = speaker ?: getString(R.string.notif_active_speaker_nobody)
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
            "ACTION_REFRESH_TYPES" -> {
                updateForegroundServiceTypeIfRunning()
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
        } else if (intent.action != "ACTION_STOP_SERVICE") {
            updateForegroundServiceTypeIfRunning()
        }

        return START_REDELIVER_INTENT
    }

    open fun updateForegroundServiceTypeIfRunning() {
        try {
            val mask = getForegroundServiceTypeMask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mask != 0) {
                startForeground(notificationId, buildTacticalNotification(), mask)
            } else {
                startForeground(notificationId, buildTacticalNotification())
            }
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError(null)
            }
        } catch (se: SecurityException) {
            android.util.Log.e("VoiceForegroundService", "updateForegroundServiceType failed SecurityException: ${se.message}")
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError("voice stopped, reopen the app")
            }
            stopSelfAction()
        } catch (e: Exception) {
            android.util.Log.e("VoiceForegroundService", "updateForegroundServiceType failed", e)
            if (::activeRideRepository.isInitialized) {
                activeRideRepository.setVoiceServiceError("voice stopped, reopen the app")
            }
            stopSelfAction()
        }
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
                    it.acquire(4 * 60 * 60 * 1000L) // 4 hours safety timeout cap (WP-143 / WP-120)
                    com.ridervoice.diagnostics.PowerProbe.recordWakeLockAcquired("VoiceFGS")
                    com.ridervoice.utils.RvLog.d("BAT", "WakeLock acquired while connected/reconnecting")
                }
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    com.ridervoice.diagnostics.PowerProbe.recordWakeLockReleased("VoiceFGS")
                    com.ridervoice.utils.RvLog.d("BAT", "WakeLock released")
                }
            }
        }
    }

    internal fun getForegroundServiceTypeMask(): Int {
        val sdk = sdkIntProvider()
        if (sdk < Build.VERSION_CODES.Q) return 0
        var mask = 0
        if (permissionChecker(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        val hasFine = permissionChecker(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = permissionChecker(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasLocationPerm = hasFine || hasCoarse
        val locationStartAllowed = if (sdk >= Build.VERSION_CODES.S) {
            appVisibilityProvider()
        } else {
            true
        }
        if (sdk >= Build.VERSION_CODES.R && hasLocationPerm && locationStartAllowed) {
            mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (permissionChecker(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                mask = mask or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
        return mask
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_hud_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_hud_desc)
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
        collapsedViews.setTextViewText(R.id.text_active_speaker, getString(R.string.notif_active_speaker_collapsed, currentActiveSpeaker))

        expandedViews.setTextViewText(R.id.text_convoy_status_expanded, currentConvoyState)
        expandedViews.setTextViewText(R.id.text_active_speaker_expanded, getString(R.string.notif_active_speaker_expanded, currentActiveSpeaker))

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
        isRunning = false
        serviceScope.cancel()
        locationService.stopTracking()
        rideRecorder.stopRecording()
        thermalManager.stopMonitoring()
        serviceWatchdog.stopMonitoring()
        networkDiagnosticsCollector.stopSession()
        liveKitManager.disconnect()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                com.ridervoice.diagnostics.PowerProbe.recordWakeLockReleased("VoiceFGS")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            internal set

        fun refreshTypes(context: Context) {
            if (!isRunning) return
            try {
                val intent = Intent(context, VoiceForegroundService::class.java).apply {
                    action = "ACTION_REFRESH_TYPES"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("VoiceForegroundService", "refreshTypes failed: ${e.message}")
            }
        }

        fun start(context: Context) {
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                android.util.Log.w("VoiceForegroundService", "start refused: RECORD_AUDIO missing")
                return
            }
            val intent = Intent(context, VoiceForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Handles ForegroundServiceStartNotAllowedException on Android 12+ and IllegalStateException
                android.util.Log.w("VoiceForegroundService", "startForegroundService failed: ${e.javaClass.simpleName}", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }
}

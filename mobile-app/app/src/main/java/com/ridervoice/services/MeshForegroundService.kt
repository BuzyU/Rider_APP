package com.ridervoice.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ridervoice.MainActivity
import com.ridervoice.R
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject
    lateinit var meshTransport: OfflineMeshVoiceTransport

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observerJob: Job? = null
    private var stateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "CHANNEL_OFFLINE_MESH"
        const val NOTIFICATION_ID = 45454
        const val ACTION_STOP_SERVICE = "com.ridervoice.mesh.ACTION_STOP"
        const val EXTRA_SESSION_CODE = "extra_session_code"

        /** Shared reference to active mesh transport for HUD and service */
        @Volatile
        var activeTransport: OfflineMeshVoiceTransport? = null
            internal set

        fun start(context: Context, sessionCode: String) {
            if (!OfflineMeshGate.isOfflineAllowed()) {
                return
            }
            val transport = activeTransport
            if (transport == null || transport.state.value != VoiceTransportState.CONNECTED) {
                return
            }
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_CODE, sessionCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var currentSessionCode: String = "Active"
    private var currentPeerCount: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!OfflineMeshGate.isOfflineAllowed()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val transport = activeTransport
        if (transport == null || transport.state.value != VoiceTransportState.CONNECTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentSessionCode = intent?.getStringExtra(EXTRA_SESSION_CODE) ?: "Active"

        val notification = buildNotification(currentSessionCode, currentPeerCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            activeTransport?.participants?.collectLatest { peers ->
                val onlineCount = peers.count { it.isOnline }
                if (onlineCount != currentPeerCount) {
                    currentPeerCount = onlineCount
                    val updated = buildNotification(currentSessionCode, currentPeerCount)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, updated)
                }
            }
        }

        stateJob?.cancel()
        stateJob = serviceScope.launch {
            activeTransport?.state?.collectLatest { state ->
                if (state == VoiceTransportState.FAILED || state == VoiceTransportState.STOPPED) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        stateJob?.cancel()
        serviceScope.cancel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Mesh Intercom",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active offline mesh intercom status"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionCode: String, peerCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "Offline Mesh: $sessionCode | $peerCount peers"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Offline Mesh Intercom Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}

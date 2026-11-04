package com.ridervoice.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ridervoice.MainActivity
import com.ridervoice.R
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSessionHolder
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

    sealed interface FgsDecision {
        data class Start(val withType: Boolean) : FgsDecision
        object Stop : FgsDecision
    }

    companion object {
        private const val TAG = "MeshFgs"
        const val CHANNEL_ID = "CHANNEL_OFFLINE_MESH"
        const val NOTIFICATION_ID = 45454
        const val ACTION_START = "com.ridervoice.mesh.action.START"
        const val ACTION_STOP = "com.ridervoice.mesh.ACTION_STOP"
        const val ACTION_STOP_SERVICE = ACTION_STOP

        fun decideForeground(action: String?): FgsDecision {
            return if (action == ACTION_STOP || action == ACTION_STOP_SERVICE) {
                FgsDecision.Stop
            } else {
                FgsDecision.Start(withType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            }
        }

        fun start(context: Context) {
            if (!OfflineMeshGate.isOfflineAllowed()) return
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                MeshLog.w(TAG, "start refused: RECORD_AUDIO missing")
                return
            }
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }
    }

    private var currentPeerCount: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = decideForeground(intent?.action)
        if (decision is FgsDecision.Stop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            MeshLog.w(TAG, "RECORD_AUDIO missing; refusing startForeground")
            stopSelf()
            return START_NOT_STICKY
        }

        val session = OfflineSessionHolder.currentSession
        val sessionDisplay = session?.let { "ID: ${it.sessionId.take(8)}" } ?: "Active"
        val notification = buildNotification(sessionDisplay, currentPeerCount)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            MeshLog.w(TAG, "startForeground denied: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            MeshLog.w(TAG, "startForeground not allowed: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!OfflineMeshGate.isOfflineAllowed() || meshTransport.state.value != VoiceTransportState.CONNECTED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            meshTransport.participants.collectLatest { peers ->
                val onlineCount = peers.count { it.isOnline }
                if (onlineCount != currentPeerCount) {
                    currentPeerCount = onlineCount
                    val updated = buildNotification(sessionDisplay, currentPeerCount)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, updated)
                }
            }
        }

        stateJob?.cancel()
        stateJob = serviceScope.launch {
            meshTransport.state.collectLatest { state ->
                if (state == VoiceTransportState.FAILED || state == VoiceTransportState.STOPPED) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
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
                getString(R.string.mesh_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionDisplay: String, peerCount: Int): Notification {
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

        val contentText = getString(R.string.mesh_notif_content, sessionDisplay, peerCount)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.mesh_notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.mesh_notif_disconnect), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}

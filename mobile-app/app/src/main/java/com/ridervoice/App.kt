package com.ridervoice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ridervoice.diagnostics.data.dao.DiagSessionDao
import com.ridervoice.diagnostics.upload.DiagnosticUploadManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionDao: DiagSessionDao

    @Inject
    lateinit var uploadManager: DiagnosticUploadManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        private const val TAG = "RiderVoiceApp"
        const val CRASH_FILE_NAME = "last_crash.txt"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        createNotificationChannels()
        finalizeOrphanedDiagnosticSessions()
    }

    private fun finalizeOrphanedDiagnosticSessions() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val orphaned = sessionDao.getOrphanedSessions()
                for (session in orphaned) {
                    Log.i(TAG, "Finalizing orphaned diagnostic session from previous run: ${session.localSessionId}")
                    uploadManager.enqueueStop(session.localSessionId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to finalize orphaned diagnostic sessions: ${e.message}")
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL UNCAUGHT EXCEPTION in thread: ${thread.name}", throwable)

                // 1. Record in Firebase Crashlytics if initialized
                try {
                    FirebaseCrashlytics.getInstance().recordException(throwable)
                } catch (_: Throwable) {}

                // 2. Persist diagnostic dump to filesDir
                val crashFile = File(filesDir, CRASH_FILE_NAME)
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("=== RIDERVOICE CRASH REPORT ===")
                    appendLine("Time: $timeStr")
                    appendLine("Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name} (id ${thread.id})")
                    appendLine()
                    appendLine("Exception: ${throwable::class.java.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine("Stack Trace:")
                    appendLine(throwable.stackTraceToString())
                }
                crashFile.writeText(report)
            } catch (loggingEx: Throwable) {
                Log.e(TAG, "Failed to persist crash report: ${loggingEx.message}")
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
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

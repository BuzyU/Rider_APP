package com.ridervoice.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ridervoice.BuildConfig
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import dagger.hilt.android.EntryPointAccessors

/**
 * Debug BroadcastReceiver available exclusively in debug builds.
 * Enables automated QA, adb shell test harness triggers, and state inspection:
 *
 * adb shell am broadcast -a com.ridervoice.mesh.DEBUG_DUMP_STATE
 * adb shell am broadcast -a com.ridervoice.mesh.DEBUG_CLEAR_SESSION
 */
class MeshDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        val action = intent.action ?: return
        Log.i(TAG, "Received debug action: $action")

        when (action) {
            ACTION_DUMP_STATE -> {
                val session = OfflineSessionHolder.get()
                val gateOpen = OfflineMeshGate.isOfflineAllowed()
                Log.i(TAG, "Debug State Dump: GateOpen=$gateOpen, ActiveSession=${session?.pairingCode?.take(4)}****")
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        MeshDebugEntryPoint::class.java
                    )
                    val transport = entryPoint.offlineMeshVoiceTransport()
                    val diag = transport.diagnostics.value
                    Log.i(TAG, "Transport state: ${transport.state.value}, Diag: members=${diag.memberCount}, routes=${diag.routeCount}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve transport entry point in debug receiver: ${e.message}")
                }
            }
            ACTION_CLEAR_SESSION -> {
                OfflineSessionHolder.clear()
                Log.i(TAG, "Cleared OfflineSessionHolder via debug broadcast")
            }
        }
    }

    companion object {
        private const val TAG = "MeshDebugReceiver"
        const val ACTION_DUMP_STATE = "com.ridervoice.mesh.DEBUG_DUMP_STATE"
        const val ACTION_CLEAR_SESSION = "com.ridervoice.mesh.DEBUG_CLEAR_SESSION"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MeshDebugEntryPoint {
    fun offlineMeshVoiceTransport(): OfflineMeshVoiceTransport
}

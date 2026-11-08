package com.ridervoice.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ridervoice.BuildConfig
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import dagger.hilt.android.EntryPointAccessors

internal fun parsePeerFilter(allow: String?): ((String) -> Boolean)? {
    val prefixes = allow.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return if (prefixes.isEmpty()) null else { id -> prefixes.any { id.startsWith(it) } }
}

/**
 * Debug BroadcastReceiver available exclusively in debug builds.
 * Protected by android.permission.DUMP (A20, A38) so non-shell third-party apps cannot invoke it.
 *
 * adb shell am broadcast -a com.ridervoice.mesh.DEBUG_DUMP_STATE
 * adb shell am broadcast -a com.ridervoice.mesh.DEBUG_CLEAR_SESSION
 * adb shell am broadcast -a com.ridervoice.debug.MESH_SET_PEER_FILTER --es allow "peer1,peer2"
 */
class MeshDebugReceiver(
    private val isDebug: Boolean = BuildConfig.DEBUG
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || !isDebug) return

        val action = intent.action ?: return
        Log.i(TAG, "Received debug action: $action")

        when (action) {
            ACTION_DUMP_STATE -> {
                val entryPoint = try {
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        MeshDebugEntryPoint::class.java
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve entry point in debug receiver: ${e.message}")
                    null
                }
                val session = entryPoint?.offlineSessionHolder()?.get()
                val gateOpen = OfflineMeshGate.isOfflineAllowed()
                val sessionTruncated = session?.let { it.sessionId.take(8) } ?: "none"
                // Security rule (A20): log only truncated sessionId, NEVER session code or secret keys
                Log.i(TAG, "Debug State Dump: GateOpen=$gateOpen, SessionId=$sessionTruncated")
                val transport = entryPoint?.offlineMeshVoiceTransport()
                if (transport != null) {
                    val diag = transport.diagnostics.value
                    val hasFilter = com.ridervoice.mesh.forwarding.PeerFilterHolder.port != null
                    Log.i(TAG, "Transport state: ${transport.state.value}, Diag: members=${diag.memberCount}, routes=${diag.routeCount}, PeerFilterActive=$hasFilter")
                }
            }
            ACTION_CLEAR_SESSION -> {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        MeshDebugEntryPoint::class.java
                    )
                    entryPoint.offlineSessionHolder().clearAll()
                    Log.i(TAG, "Cleared OfflineSessionHolder via debug broadcast")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve entry point in debug receiver: ${e.message}")
                }
            }
            ACTION_SET_PEER_FILTER, ACTION_LEGACY_SET_PEER_FILTER -> {
                val allowExtra = intent.getStringExtra(EXTRA_ALLOW) ?: intent.getStringExtra(EXTRA_ALLOWED_PEERS)
                val filter = parsePeerFilter(allowExtra)
                val port = if (filter != null) com.ridervoice.mesh.forwarding.PeerFilterPort { filter(it) } else null
                com.ridervoice.mesh.forwarding.PeerFilterHolder.port = port
                Log.i(TAG, "Updated PeerFilterHolder.port: ${port != null}")
            }
        }
    }

    companion object {
        private const val TAG = "MeshDebugReceiver"
        const val ACTION_DUMP_STATE = "com.ridervoice.mesh.DEBUG_DUMP_STATE"
        const val ACTION_CLEAR_SESSION = "com.ridervoice.mesh.DEBUG_CLEAR_SESSION"
        const val ACTION_SET_PEER_FILTER = "com.ridervoice.debug.MESH_SET_PEER_FILTER"
        const val ACTION_LEGACY_SET_PEER_FILTER = "com.ridervoice.mesh.DEBUG_SET_PEER_FILTER"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_ALLOWED_PEERS = "allowed_peers"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MeshDebugEntryPoint {
    fun offlineMeshVoiceTransport(): OfflineMeshVoiceTransport
    fun offlineSessionHolder(): OfflineSessionHolder
}

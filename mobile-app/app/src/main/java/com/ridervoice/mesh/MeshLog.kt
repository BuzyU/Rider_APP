package com.ridervoice.mesh

import android.util.Log

/**
 * JVM-test-safe logger. Never pass secrets (pairing code, keys). Device ids: first 8 chars only.
 */
object MeshLog {
    private const val TAG = "RVMesh"
    fun d(sub: String, msg: String) = safe { Log.d(TAG, "[$sub] $msg") }
    fun w(sub: String, msg: String) = safe { Log.w(TAG, "[$sub] $msg") }
    fun e(sub: String, msg: String) = safe { Log.e(TAG, "[$sub] $msg") }
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // "Stub!" on plain JVM
        }
    }
}

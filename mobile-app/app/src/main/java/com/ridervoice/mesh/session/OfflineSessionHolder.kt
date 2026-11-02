package com.ridervoice.mesh.session

/**
 * In-memory holder for the active offline mesh session.
 * Retains session across Activity configuration changes (rotations)
 * without persisting secrets to disk or embedding them into navigation route arguments.
 */
object OfflineSessionHolder {
    @Volatile
    var currentSession: OfflineSession? = null

    fun set(session: OfflineSession?) {
        currentSession = session
    }

    fun get(): OfflineSession? = currentSession

    fun clear() {
        currentSession = null
    }
}


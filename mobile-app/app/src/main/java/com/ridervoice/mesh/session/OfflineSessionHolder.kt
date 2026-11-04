package com.ridervoice.mesh.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory holder for the active offline mesh session and online ride backup session.
 * Retains session across Activity configuration changes (rotations)
 * without persisting secrets to disk or embedding them into navigation route arguments.
 */
@Singleton
class OfflineSessionHolder @Inject constructor() {
    private val _current = MutableStateFlow<OfflineSession?>(null)
    private val _backup = MutableStateFlow<OfflineSession?>(null)
    val current: StateFlow<OfflineSession?> = _current.asStateFlow()
    val backup: StateFlow<OfflineSession?> = _backup.asStateFlow()

    fun setCurrent(s: OfflineSession?) {
        val old = _current.getAndUpdate { s }
        if (old !== s) old?.close()
    }

    /**
     * Backup MUST be a separately derived OfflineSession instance (fromCode again, off-main),
     * so closing one never zeroes the other.
     */
    fun setBackup(s: OfflineSession?) {
        val old = _backup.getAndUpdate { s }
        if (old !== s) old?.close()
    }

    fun clearCurrent() = setCurrent(null)
    fun clearAll() {
        setCurrent(null)
        setBackup(null)
    }

    companion object {
        @Volatile
        private var globalInstance: OfflineSessionHolder? = null

        fun getHolder(): OfflineSessionHolder {
            return globalInstance ?: synchronized(this) {
                globalInstance ?: OfflineSessionHolder().also { globalInstance = it }
            }
        }

        var currentSession: OfflineSession?
            get() = getHolder().current.value
            set(value) { getHolder().setCurrent(value) }

        fun set(session: OfflineSession?) { getHolder().setCurrent(session) }
        fun get(): OfflineSession? = getHolder().current.value
        fun clear() { getHolder().clearCurrent() }

        fun setBackup(session: OfflineSession?) { getHolder().setBackup(session) }
        fun getBackup(): OfflineSession? = getHolder().backup.value
    }

    init {
        globalInstance = this
    }
}

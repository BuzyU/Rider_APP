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

    fun get(): OfflineSession? = _current.value
    fun set(s: OfflineSession?) = setCurrent(s)
    fun getBackup(): OfflineSession? = _backup.value

    fun clearCurrent() = setCurrent(null)
    fun clearBackup() = setBackup(null)
    fun clearAll() {
        setCurrent(null)
        setBackup(null)
    }
}

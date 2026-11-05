package com.ridervoice.mesh.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface OfflineSecretProvider {
    val backupSession: StateFlow<OfflineSession?> get() = MutableStateFlow(null)
    fun getSessionForOnlineRide(roomId: String): OfflineSession? = null
}

class NullOfflineSecretProvider : OfflineSecretProvider {
    override val backupSession: StateFlow<OfflineSession?> = MutableStateFlow(null)
    override fun getSessionForOnlineRide(roomId: String): OfflineSession? = null
}

class HolderOfflineSecretProvider(
    private val holder: OfflineSessionHolder,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : OfflineSecretProvider {
    override val backupSession: StateFlow<OfflineSession?> get() = holder.backup
    override fun getSessionForOnlineRide(roomId: String): OfflineSession? =
        holder.backup.value?.takeIf { !it.isExpired(clock()) }
}

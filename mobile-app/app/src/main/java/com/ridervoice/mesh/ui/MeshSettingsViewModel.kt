package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeshSettingsUiState(
    val buildGateOpen: Boolean = false,
    val offlineMeshEnabled: Boolean = false,
    val autoFallback: Boolean = false,
    val warmStandby: Boolean = false,
    val offlineAvailability: OfflineAvailability = OfflineAvailability.DISABLED,
    val backupSessionPresent: Boolean = false,
    val backupPersisted: Boolean = true,
    val hasWifiHardware: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MeshSettingsViewModel(
    private val settings: MeshRuntimeSettings,
    private val coordinator: dagger.Lazy<VoiceSessionCoordinator>,
    private val wifiCapability: WifiCapabilityChecker,
    private val sessionHolder: dagger.Lazy<OfflineSessionHolder>,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val overrideAvailabilityFlow: StateFlow<OfflineAvailability>? = null
) : ViewModel() {

    @Inject
    constructor(
        settings: MeshRuntimeSettings,
        coordinator: dagger.Lazy<VoiceSessionCoordinator>,
        wifiCapability: WifiCapabilityChecker,
        sessionHolder: dagger.Lazy<OfflineSessionHolder>,
        @MeshApplicationScope appScope: CoroutineScope
    ) : this(
        settings = settings,
        coordinator = coordinator,
        wifiCapability = wifiCapability,
        sessionHolder = sessionHolder,
        appScope = appScope,
        ioDispatcher = Dispatchers.IO,
        overrideAvailabilityFlow = null
    )

    constructor(
        settings: MeshRuntimeSettings,
        availabilityFlow: StateFlow<OfflineAvailability>,
        wifiCapability: WifiCapabilityChecker,
        sessionHolder: OfflineSessionHolder
    ) : this(
        settings = settings,
        coordinator = dagger.Lazy { error("Coordinator not available") },
        wifiCapability = wifiCapability,
        sessionHolder = dagger.Lazy { sessionHolder },
        overrideAvailabilityFlow = availabilityFlow
    )

    constructor(
        settings: MeshRuntimeSettings,
        availabilityFlow: StateFlow<OfflineAvailability>,
        wifiCapability: WifiCapabilityChecker
    ) : this(
        settings = settings,
        availabilityFlow = availabilityFlow,
        wifiCapability = wifiCapability,
        sessionHolder = OfflineSessionHolder()
    )

    constructor(
        settings: MeshRuntimeSettings,
        availabilityFlow: StateFlow<OfflineAvailability>
    ) : this(
        settings,
        availabilityFlow,
        object : WifiCapabilityChecker { override fun isWifiHardwareAvailable(): Boolean = true },
        OfflineSessionHolder()
    )

    constructor(settings: MeshRuntimeSettings) : this(
        settings,
        MutableStateFlow(OfflineAvailability.DISABLED)
    )

    val availabilityFlow: StateFlow<OfflineAvailability> = overrideAvailabilityFlow
        ?: settings.settingsFlow.flatMapLatest { snapshot ->
            if (OfflineMeshGate.isBuildGateOpen() && snapshot.offlineMeshEnabled) {
                coordinator.get().offlineAvailability
            } else {
                flowOf(OfflineAvailability.DISABLED)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineAvailability.DISABLED)

    val backupFlow: StateFlow<com.ridervoice.mesh.session.OfflineSession?> = settings.settingsFlow.flatMapLatest { snapshot ->
        if (OfflineMeshGate.isBuildGateOpen() && snapshot.offlineMeshEnabled) {
            sessionHolder.get().backup
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun getBackup(): com.ridervoice.mesh.session.OfflineSession? {
        if (!OfflineMeshGate.isBuildGateOpen() || !settings.offlineMeshEnabled) return null
        return sessionHolder.get().getBackup()
    }

    private fun isBackupPersisted(): Boolean {
        if (!OfflineMeshGate.isBuildGateOpen() || !settings.offlineMeshEnabled) return true
        return sessionHolder.get().isBackupPersisted
    }

    val uiState: StateFlow<MeshSettingsUiState> = combine(
        settings.settingsFlow,
        availabilityFlow,
        backupFlow
    ) { snapshot, availability, backup ->
        val now = System.currentTimeMillis()
        val backupValid = backup != null && !backup.isExpired(now)
        val hasWifi = wifiCapability.isWifiHardwareAvailable()
        val persisted = if (backupValid) isBackupPersisted() else true
        MeshSettingsUiState(
            buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
            offlineMeshEnabled = snapshot.offlineMeshEnabled,
            autoFallback = snapshot.automaticFallbackEnabled && hasWifi,
            warmStandby = snapshot.warmStandbyEnabled,
            offlineAvailability = availability,
            backupSessionPresent = backupValid,
            backupPersisted = persisted,
            hasWifiHardware = hasWifi
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MeshSettingsUiState(
            buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
            offlineMeshEnabled = settings.offlineMeshEnabled,
            autoFallback = settings.automaticFallbackEnabled && wifiCapability.isWifiHardwareAvailable(),
            warmStandby = settings.warmStandbyEnabled,
            offlineAvailability = overrideAvailabilityFlow?.value ?: OfflineAvailability.DISABLED,
            backupSessionPresent = false,
            backupPersisted = true,
            hasWifiHardware = wifiCapability.isWifiHardwareAvailable()
        )
    )

    fun setOfflineMeshEnabled(enabled: Boolean) {
        settings.setOfflineMeshEnabled(enabled)
        if (!enabled) {
            appScope.launch(ioDispatcher) { sessionHolder.get().clearBackup() }
        }
    }

    fun setAutomaticFallbackEnabled(enabled: Boolean) {
        if (enabled && !wifiCapability.isWifiHardwareAvailable()) {
            settings.setAutomaticFallbackEnabled(false)
            return
        }
        settings.setAutomaticFallbackEnabled(enabled)
    }

    fun setWarmStandbyEnabled(enabled: Boolean) {
        settings.setWarmStandbyEnabled(enabled)
    }

    fun clearAllOnSignOut() {
        appScope.launch(ioDispatcher) { sessionHolder.get().clearAll() }
    }
}

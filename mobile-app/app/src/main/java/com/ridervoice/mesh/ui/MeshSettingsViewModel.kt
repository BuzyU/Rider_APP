package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
class MeshSettingsViewModel : ViewModel {

    private val settings: MeshRuntimeSettings
    private val wifiCapability: WifiCapabilityChecker
    private val appScope: CoroutineScope
    private val holderForWipe: () -> OfflineSessionHolder?

    val availabilityFlow: StateFlow<OfflineAvailability>
    val backupFlow: StateFlow<OfflineSession?>
    val uiState: StateFlow<MeshSettingsUiState>

    @Inject
    constructor(
        settings: MeshRuntimeSettings,
        coordinator: dagger.Lazy<VoiceSessionCoordinator>,
        wifiCapability: WifiCapabilityChecker,
        sessionHolder: dagger.Lazy<OfflineSessionHolder>,
        @MeshApplicationScope appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) : super() {
        this.settings = settings
        this.wifiCapability = wifiCapability
        this.appScope = appScope
        this.holderForWipe = { sessionHolder.get() }

        val avail = settings.settingsFlow.flatMapLatest { snapshot ->
            if (OfflineMeshGate.isBuildGateOpen() && snapshot.offlineMeshEnabled) {
                coordinator.get().offlineAvailability
            } else {
                flowOf(OfflineAvailability.DISABLED)
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, OfflineAvailability.DISABLED)
        this.availabilityFlow = avail

        val bkp = settings.settingsFlow.flatMapLatest { snapshot ->
            if (OfflineMeshGate.isBuildGateOpen() && snapshot.offlineMeshEnabled) {
                sessionHolder.get().backup
            } else {
                flowOf(null)
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        this.backupFlow = bkp

        val isPersistedCheck = {
            if (!OfflineMeshGate.isBuildGateOpen() || !settings.offlineMeshEnabled) true
            else sessionHolder.get().isBackupPersisted
        }

        this.uiState = combine(
            settings.settingsFlow,
            avail,
            bkp
        ) { snapshot, availValue, bkpValue ->
            val now = System.currentTimeMillis()
            val backupValid = bkpValue != null && !bkpValue.isExpired(now)
            val hasWifi = wifiCapability.isWifiHardwareAvailable()
            val persisted = if (backupValid) isPersistedCheck() else true
            MeshSettingsUiState(
                buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
                offlineMeshEnabled = snapshot.offlineMeshEnabled,
                autoFallback = snapshot.automaticFallbackEnabled && hasWifi,
                warmStandby = snapshot.warmStandbyEnabled,
                offlineAvailability = availValue,
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
                offlineAvailability = OfflineAvailability.DISABLED,
                backupSessionPresent = false,
                backupPersisted = true,
                hasWifiHardware = wifiCapability.isWifiHardwareAvailable()
            )
        )
    }

    internal constructor(
        availability: () -> Flow<OfflineAvailability>,
        backup: () -> Flow<OfflineSession?>,
        holderForWipe: () -> OfflineSessionHolder,
        wifiCapability: WifiCapabilityChecker,
        settings: MeshRuntimeSettings
    ) : super() {
        this.settings = settings
        this.wifiCapability = wifiCapability
        this.appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.holderForWipe = holderForWipe

        val avail = availability().stateIn(viewModelScope, SharingStarted.Eagerly, OfflineAvailability.DISABLED)
        this.availabilityFlow = avail

        val bkp = backup().stateIn(viewModelScope, SharingStarted.Eagerly, null)
        this.backupFlow = bkp

        val isPersistedCheck = {
            holderForWipe().isBackupPersisted
        }

        this.uiState = combine(
            settings.settingsFlow,
            avail,
            bkp
        ) { snapshot, availValue, bkpValue ->
            val now = System.currentTimeMillis()
            val backupValid = bkpValue != null && !bkpValue.isExpired(now)
            val hasWifi = wifiCapability.isWifiHardwareAvailable()
            val persisted = if (backupValid) isPersistedCheck() else true
            MeshSettingsUiState(
                buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
                offlineMeshEnabled = snapshot.offlineMeshEnabled,
                autoFallback = snapshot.automaticFallbackEnabled && hasWifi,
                warmStandby = snapshot.warmStandbyEnabled,
                offlineAvailability = availValue,
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
                offlineAvailability = OfflineAvailability.DISABLED,
                backupSessionPresent = false,
                backupPersisted = true,
                hasWifiHardware = wifiCapability.isWifiHardwareAvailable()
            )
        )
    }

    fun setOfflineMeshEnabled(enabled: Boolean) {
        settings.setOfflineMeshEnabled(enabled)
        if (!enabled) {
            appScope.launch {
                holderForWipe()?.clearBackup()
            }
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
        appScope.launch {
            holderForWipe()?.clearAll()
        }
    }
}

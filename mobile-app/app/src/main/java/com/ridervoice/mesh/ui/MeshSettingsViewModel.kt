package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MeshSettingsUiState(
    val buildGateOpen: Boolean = false,
    val offlineMeshEnabled: Boolean = false,
    val autoFallback: Boolean = false,
    val warmStandby: Boolean = false,
    val offlineAvailability: OfflineAvailability = OfflineAvailability.DISABLED,
    val backupSessionPresent: Boolean = false,
    val hasWifiHardware: Boolean = true
)

@HiltViewModel
class MeshSettingsViewModel(
    private val settings: MeshRuntimeSettings,
    private val availabilityFlow: StateFlow<OfflineAvailability>,
    private val wifiCapability: WifiCapabilityChecker,
    private val sessionHolder: OfflineSessionHolder = OfflineSessionHolder()
) : ViewModel() {

    @Inject
    constructor(
        settings: MeshRuntimeSettings,
        coordinator: VoiceSessionCoordinator,
        wifiCapability: WifiCapabilityChecker,
        sessionHolder: OfflineSessionHolder
    ) : this(settings, coordinator.offlineAvailability, wifiCapability, sessionHolder)

    constructor(
        settings: MeshRuntimeSettings,
        availabilityFlow: StateFlow<OfflineAvailability>
    ) : this(
        settings,
        availabilityFlow,
        object : WifiCapabilityChecker { override fun isWifiHardwareAvailable(): Boolean = true }
    )

    constructor(settings: MeshRuntimeSettings) : this(
        settings,
        MutableStateFlow(OfflineAvailability.DISABLED),
        object : WifiCapabilityChecker { override fun isWifiHardwareAvailable(): Boolean = true }
    )

    val uiState: StateFlow<MeshSettingsUiState> = combine(
        settings.settingsFlow,
        availabilityFlow,
        sessionHolder.backup
    ) { snapshot, availability, backup ->
        val now = System.currentTimeMillis()
        val backupValid = backup != null && !backup.isExpired(now)
        val hasWifi = wifiCapability.isWifiHardwareAvailable()
        MeshSettingsUiState(
            buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
            offlineMeshEnabled = snapshot.offlineMeshEnabled,
            autoFallback = snapshot.automaticFallbackEnabled && hasWifi,
            warmStandby = snapshot.warmStandbyEnabled,
            offlineAvailability = availability,
            backupSessionPresent = backupValid,
            hasWifiHardware = hasWifi
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = run {
            val now = System.currentTimeMillis()
            val s = settings.settingsFlow.value
            val backup = sessionHolder.getBackup()
            val hasWifi = wifiCapability.isWifiHardwareAvailable()
            MeshSettingsUiState(
                buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
                offlineMeshEnabled = s.offlineMeshEnabled,
                autoFallback = s.automaticFallbackEnabled && hasWifi,
                warmStandby = s.warmStandbyEnabled,
                offlineAvailability = availabilityFlow.value,
                backupSessionPresent = backup != null && !backup.isExpired(now),
                hasWifiHardware = hasWifi
            )
        }
    )

    fun setOfflineMeshEnabled(enabled: Boolean) {
        settings.setOfflineMeshEnabled(enabled)
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
        sessionHolder.clearAll()
    }
}

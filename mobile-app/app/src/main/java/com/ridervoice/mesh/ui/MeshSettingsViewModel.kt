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
    val backupSessionPresent: Boolean = false
)

@HiltViewModel
class MeshSettingsViewModel(
    private val settings: MeshRuntimeSettings,
    private val availabilityFlow: StateFlow<OfflineAvailability>
) : ViewModel() {

    @Inject
    constructor(
        settings: MeshRuntimeSettings,
        coordinator: VoiceSessionCoordinator
    ) : this(settings, coordinator.offlineAvailability)

    constructor(settings: MeshRuntimeSettings) : this(
        settings,
        MutableStateFlow(OfflineAvailability.DISABLED)
    )

    val uiState: StateFlow<MeshSettingsUiState> = combine(
        settings.settingsFlow,
        availabilityFlow
    ) { snapshot, availability ->
        val now = System.currentTimeMillis()
        val backup = OfflineSessionHolder.getBackup()
        val backupValid = backup != null && !backup.isExpired(now)
        MeshSettingsUiState(
            buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
            offlineMeshEnabled = snapshot.offlineMeshEnabled,
            autoFallback = snapshot.automaticFallbackEnabled,
            warmStandby = snapshot.warmStandbyEnabled,
            offlineAvailability = availability,
            backupSessionPresent = backupValid
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = run {
            val now = System.currentTimeMillis()
            val s = settings.settingsFlow.value
            val backup = OfflineSessionHolder.getBackup()
            MeshSettingsUiState(
                buildGateOpen = OfflineMeshGate.isBuildGateOpen(),
                offlineMeshEnabled = s.offlineMeshEnabled,
                autoFallback = s.automaticFallbackEnabled,
                warmStandby = s.warmStandbyEnabled,
                offlineAvailability = availabilityFlow.value,
                backupSessionPresent = backup != null && !backup.isExpired(now)
            )
        }
    )

    fun setOfflineMeshEnabled(enabled: Boolean) {
        settings.setOfflineMeshEnabled(enabled)
    }

    fun setAutomaticFallbackEnabled(enabled: Boolean) {
        settings.setAutomaticFallbackEnabled(enabled)
    }

    fun setWarmStandbyEnabled(enabled: Boolean) {
        settings.setWarmStandbyEnabled(enabled)
    }
}

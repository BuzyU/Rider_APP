package com.ridervoice.mesh.ui

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

fun MeshSettingsViewModel(
    settings: MeshRuntimeSettings,
    availabilityFlow: Flow<OfflineAvailability> = flowOf(OfflineAvailability.DISABLED),
    wifiCapability: WifiCapabilityChecker = object : WifiCapabilityChecker {
        override fun isWifiHardwareAvailable(): Boolean = true
    },
    sessionHolder: OfflineSessionHolder = OfflineSessionHolder()
): MeshSettingsViewModel = MeshSettingsViewModel(
    availability = { availabilityFlow },
    backup = { sessionHolder.backup },
    holderForWipe = { sessionHolder },
    wifiCapability = wifiCapability,
    settings = settings
)

fun MeshSettingsViewModel(settings: MeshRuntimeSettings): MeshSettingsViewModel =
    MeshSettingsViewModel(
        settings = settings,
        availabilityFlow = flowOf(OfflineAvailability.DISABLED)
    )

fun MeshSettingsViewModel(
    settings: MeshRuntimeSettings,
    availabilityFlow: Flow<OfflineAvailability>
): MeshSettingsViewModel = MeshSettingsViewModel(
    settings = settings,
    availabilityFlow = availabilityFlow,
    wifiCapability = object : WifiCapabilityChecker {
        override fun isWifiHardwareAvailable(): Boolean = true
    },
    sessionHolder = OfflineSessionHolder()
)

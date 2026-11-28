package com.ridervoice.mesh.link

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.link.aware.WifiAwareStatus
import com.ridervoice.mesh.offline.RiderLinkLevel

enum class ActiveOfflineTransportMode(val wireName: String) {
    WIFI_AWARE_CHAIN("WIFI_AWARE_CHAIN"),
    WIFI_DIRECT_STAR("WIFI_DIRECT_STAR"),
    DISABLED_WIFI_OFF("DISABLED_WIFI_OFF"),
    MANUAL_OR_NONE("MANUAL_OR_NONE")
}

/**
 * Pure Kotlin transport selector shared by LobbyViewModel and MeshAutoSwitchManager.
 *
 * Defaults to **Wi-Fi Aware Bus/Chain** (`WIFI_AWARE_CHAIN`) whenever the device supports it,
 * and falls back automatically to **Center-Elected Wi-Fi Direct Star** (`WIFI_DIRECT_STAR`) when
 * Wi-Fi Aware is unsupported, unavailable, failed, or finds zero Aware peers in a mixed convoy.
 */
object OfflineLinkOrchestrator {

    data class Input(
        val linkLevel: RiderLinkLevel,
        val awareStatus: WifiAwareStatus,
        val awareActivePeers: Int,
        val awareDiscoveredPeers: Int,
        val awareEmptyDurationMs: Long,
        val expectedParticipantCount: Int,
        val isLiveOfflineVoice: Boolean,
        val p2pSupported: Boolean = true,
        val awareFallbackLatched: Boolean = false,
        val awareEmptyFallbackMs: Long = MeshConfig.AWARE_EMPTY_FALLBACK_MS
    )

    fun decide(input: Input): ActiveOfflineTransportMode {
        if (input.awareStatus == WifiAwareStatus.DISABLED_WIFI_OFF) {
            return ActiveOfflineTransportMode.DISABLED_WIFI_OFF
        }
        if (!input.linkLevel.supportsAutoLink) {
            return ActiveOfflineTransportMode.MANUAL_OR_NONE
        }

        val awareHardFailed = input.awareStatus in setOf(
            WifiAwareStatus.UNSUPPORTED,
            WifiAwareStatus.UNAVAILABLE,
            WifiAwareStatus.FAILED,
            WifiAwareStatus.MISSING_PERMISSION
        )

        val mixedFleetFallback = input.expectedParticipantCount >= 2 &&
            !input.isLiveOfflineVoice &&
            input.awareDiscoveredPeers == 0 &&
            input.awareActivePeers == 0 &&
            input.awareEmptyDurationMs >= input.awareEmptyFallbackMs

        if (input.linkLevel == RiderLinkLevel.AUTO_AWARE_CHAIN &&
            !input.awareFallbackLatched &&
            !awareHardFailed &&
            !mixedFleetFallback
        ) {
            return ActiveOfflineTransportMode.WIFI_AWARE_CHAIN
        }

        return if (input.p2pSupported) {
            ActiveOfflineTransportMode.WIFI_DIRECT_STAR
        } else {
            ActiveOfflineTransportMode.MANUAL_OR_NONE
        }
    }
}

package com.ridervoice.mesh.link

import com.ridervoice.mesh.link.aware.WifiAwareStatus
import com.ridervoice.mesh.offline.RiderLinkLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLinkOrchestratorTest {

    @Test
    fun awareCapablePhone_defaultsToWifiAwareChain() {
        val mode = OfflineLinkOrchestrator.decide(
            OfflineLinkOrchestrator.Input(
                linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                awareStatus = WifiAwareStatus.DISCOVERING,
                awareActivePeers = 1,
                awareDiscoveredPeers = 2,
                awareEmptyDurationMs = 0L,
                expectedParticipantCount = 4,
                isLiveOfflineVoice = false
            )
        )
        assertEquals(ActiveOfflineTransportMode.WIFI_AWARE_CHAIN, mode)
    }

    @Test
    fun awareEmptyForFallbackWindowInMultiRiderLobby_autoFallsBackToWifiDirectStar() {
        val mode = OfflineLinkOrchestrator.decide(
            OfflineLinkOrchestrator.Input(
                linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                awareStatus = WifiAwareStatus.DISCOVERING,
                awareActivePeers = 0,
                awareDiscoveredPeers = 0,
                awareEmptyDurationMs = 15_500L,
                expectedParticipantCount = 3,
                isLiveOfflineVoice = false
            )
        )
        assertEquals(ActiveOfflineTransportMode.WIFI_DIRECT_STAR, mode)
    }

    @Test
    fun liveOfflineVoiceNeverDropsAwareOnZeroIdleTimeout_andHardFailuresFallbackToP2p() {
        val duringLiveVoice = OfflineLinkOrchestrator.decide(
            OfflineLinkOrchestrator.Input(
                linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                awareStatus = WifiAwareStatus.DISCOVERING,
                awareActivePeers = 0,
                awareDiscoveredPeers = 0,
                awareEmptyDurationMs = 30_000L,
                expectedParticipantCount = 3,
                isLiveOfflineVoice = true
            )
        )
        assertEquals(ActiveOfflineTransportMode.WIFI_AWARE_CHAIN, duringLiveVoice)

        val onAwareUnavailable = OfflineLinkOrchestrator.decide(
            OfflineLinkOrchestrator.Input(
                linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                awareStatus = WifiAwareStatus.UNAVAILABLE,
                awareActivePeers = 0,
                awareDiscoveredPeers = 0,
                awareEmptyDurationMs = 0L,
                expectedParticipantCount = 3,
                isLiveOfflineVoice = false
            )
        )
        assertEquals(ActiveOfflineTransportMode.WIFI_DIRECT_STAR, onAwareUnavailable)
    }

    @Test
    fun wifiOffAndManualLevelsHandledCleanly() {
        assertEquals(
            ActiveOfflineTransportMode.DISABLED_WIFI_OFF,
            OfflineLinkOrchestrator.decide(
                OfflineLinkOrchestrator.Input(
                    linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                    awareStatus = WifiAwareStatus.DISABLED_WIFI_OFF,
                    awareActivePeers = 0,
                    awareDiscoveredPeers = 0,
                    awareEmptyDurationMs = 0L,
                    expectedParticipantCount = 3,
                    isLiveOfflineVoice = false
                )
            )
        )
        assertEquals(
            ActiveOfflineTransportMode.MANUAL_OR_NONE,
            OfflineLinkOrchestrator.decide(
                OfflineLinkOrchestrator.Input(
                    linkLevel = RiderLinkLevel.MANUAL_HOTSPOT,
                    awareStatus = WifiAwareStatus.IDLE,
                    awareActivePeers = 0,
                    awareDiscoveredPeers = 0,
                    awareEmptyDurationMs = 0L,
                    expectedParticipantCount = 3,
                    isLiveOfflineVoice = false
                )
            )
        )
    }

    @Test
    fun awareFallbackLatched_holdsWifiDirectStarWithoutPingPongOnIdleAware() {
        val mode = OfflineLinkOrchestrator.decide(
            OfflineLinkOrchestrator.Input(
                linkLevel = RiderLinkLevel.AUTO_AWARE_CHAIN,
                awareStatus = WifiAwareStatus.IDLE,
                awareActivePeers = 0,
                awareDiscoveredPeers = 0,
                awareEmptyDurationMs = 0L,
                expectedParticipantCount = 3,
                isLiveOfflineVoice = false,
                awareFallbackLatched = true
            )
        )
        assertEquals(ActiveOfflineTransportMode.WIFI_DIRECT_STAR, mode)
    }
}

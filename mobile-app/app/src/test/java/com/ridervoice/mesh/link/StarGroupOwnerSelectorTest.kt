package com.ridervoice.mesh.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarGroupOwnerSelectorTest {

    @Test
    fun incapableHost_electsFirstEligibleMeshParticipant() {
        val selected = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = listOf("rider-b", "rider-c"),
                hostUserId = "host-api27"
            )
        )
        assertEquals("rider-b", selected)
    }

    @Test
    fun connectedEligibleGo_doesNotChurnOnOnlineHostTransferOrLeave() {
        val selected = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = listOf("rider-a", "rider-b", "rider-c"),
                hostUserId = "rider-c", // online host transferred to rider-c
                previousGoId = "rider-a",
                isP2pConnected = true,
                isLiveOfflineVoiceActive = false
            )
        )
        assertEquals("rider-a", selected)
    }

    @Test
    fun liveOfflineVoice_freezesExistingGoWithoutReelection() {
        val selected = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = listOf("rider-a", "rider-b", "rider-c"),
                hostUserId = "rider-c",
                previousGoId = "rider-b",
                isP2pConnected = false,
                isLiveOfflineVoiceActive = true,
                positions = mapOf(
                    "rider-a" to (18.5204 to 73.8567),
                    "rider-b" to (18.5250 to 73.8600),
                    "rider-c" to (18.5205 to 73.8568)
                ),
                forceReelect = false
            )
        )
        assertEquals("rider-b", selected)
    }

    @Test
    fun goDeath_excludesDeadGoAndPromotesNextCanonicalMember() {
        val selected = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = listOf("host-a", "rider-b", "rider-c"),
                hostUserId = "host-a",
                previousGoId = "host-a",
                isP2pConnected = false,
                isLiveOfflineVoiceActive = true,
                excludedGoIds = setOf("host-a"),
                forceReelect = true
            )
        )
        assertEquals("rider-b", selected)

        assertNull(
            StarGroupOwnerSelector.selectGoId(
                StarGroupOwnerSelector.Input(
                    meshParticipants = listOf("host-a"),
                    hostUserId = "host-a",
                    excludedGoIds = setOf("host-a")
                )
            )
        )
    }

    @Test
    fun positionsKnown_selectsMostCentralEligibleRider() {
        // B sits in the middle (~110m from A and ~110m from C); A and C are ~220m apart.
        val selected = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = listOf("rider-a", "rider-b", "rider-c"),
                hostUserId = "rider-a",
                positions = mapOf(
                    "rider-a" to (18.5200 to 73.8500),
                    "rider-b" to (18.5210 to 73.8500),
                    "rider-c" to (18.5220 to 73.8500)
                )
            )
        )
        assertEquals("rider-b", selected)
    }
}

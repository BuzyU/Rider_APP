package com.ridervoice.mesh.topology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyEngineTest {

    @Test
    fun northSouthRoad_producesIdenticalChainOrderingToEastWest() {
        val participants = listOf("r1", "r2", "r3", "r4")
        val dualP2p = setOf("r1", "r2", "r3", "r4")

        // North-South convoy (spaced ~111 m apart along latitude)
        val nsPositions = mapOf(
            "r1" to PositionFix("r1", 100, 18_520_000, 73_850_000, 50),
            "r2" to PositionFix("r2", 100, 18_521_000, 73_850_000, 50),
            "r3" to PositionFix("r3", 100, 18_522_000, 73_850_000, 50),
            "r4" to PositionFix("r4", 100, 18_523_000, 73_850_000, 50)
        )
        // East-West convoy (spaced ~105 m apart along longitude)
        val ewPositions = mapOf(
            "r1" to PositionFix("r1", 100, 18_520_000, 73_850_000, 50),
            "r2" to PositionFix("r2", 100, 18_520_000, 73_851_000, 50),
            "r3" to PositionFix("r3", 100, 18_520_000, 73_852_000, 50),
            "r4" to PositionFix("r4", 100, 18_520_000, 73_853_000, 50)
        )

        val nsPlan = TopologyEngine.computePlan(participants, nsPositions, dualP2p, currentHostId = "r1")
        val ewPlan = TopologyEngine.computePlan(participants, ewPositions, dualP2p, currentHostId = "r1")

        assertTrue(nsPlan.isChainMode)
        assertTrue(ewPlan.isChainMode)
        assertEquals(listOf("r1", "r2", "r3", "r4"), nsPlan.orderedChain)
        assertEquals(nsPlan.orderedChain, ewPlan.orderedChain)
        assertEquals(TopologyRole.CHAIN_LEAF, nsPlan.roles["r1"])
        assertEquals(TopologyRole.CHAIN_BRIDGE, nsPlan.roles["r2"])
        assertEquals(TopologyRole.CHAIN_BRIDGE, nsPlan.roles["r3"])
        assertEquals(TopologyRole.CHAIN_LEAF, nsPlan.roles["r4"])
        assertEquals(3, nsPlan.segments.size)
        assertEquals(ChainSegment("r1", "r2", 0, 1), nsPlan.segments[0])
        assertEquals(ChainSegment("r2", "r3", 1, 2), nsPlan.segments[1])
        assertEquals(ChainSegment("r3", "r4", 2, 3), nsPlan.segments[2])
    }

    @Test
    fun curvedSwitchback_findsMinimumLengthChainWithinThreshold() {
        val participants = listOf("a", "b", "c", "d")
        val dualP2p = setOf("a", "b", "c", "d")
        // Hairpin road: a -> c -> d -> b (shuffled IDs along U-bend)
        val positions = mapOf(
            "a" to PositionFix("a", 100, 18_520_000, 73_850_000, 50),
            "c" to PositionFix("c", 100, 18_521_000, 73_850_800, 50),
            "d" to PositionFix("d", 100, 18_521_100, 73_851_800, 50),
            "b" to PositionFix("b", 100, 18_520_100, 73_852_600, 50)
        )
        val plan = TopologyEngine.computePlan(participants, positions, dualP2p, currentHostId = "a")
        assertTrue(plan.isChainMode)
        assertEquals(listOf("a", "c", "d", "b"), plan.orderedChain)
    }

    @Test
    fun spreadBelowThresholdOrNoDualP2p_fallsBackToStarHub() {
        val participants = listOf("r1", "r2", "r3")
        // Compact pack (~11m apart <= 50m threshold) -> Star mode
        val compactPositions = mapOf(
            "r1" to PositionFix("r1", 100, 18_520_000, 73_850_000, 50),
            "r2" to PositionFix("r2", 100, 18_520_100, 73_850_000, 50),
            "r3" to PositionFix("r3", 100, 18_520_200, 73_850_000, 50)
        )
        val compactPlan = TopologyEngine.computePlan(
            participants, compactPositions, setOf("r1", "r2", "r3"), currentHostId = "r1"
        )
        assertFalse(compactPlan.isChainMode)
        assertEquals("r2", compactPlan.starGoId) // most central rider in Star mode
        assertEquals(TopologyRole.STAR_GO, compactPlan.roles["r2"])

        // Wide convoy (> 50m), but middle node r2 lacks dual-P2P -> collapses to Star
        val widePositions = mapOf(
            "r1" to PositionFix("r1", 100, 18_520_000, 73_850_000, 50),
            "r2" to PositionFix("r2", 100, 18_521_000, 73_850_000, 50),
            "r3" to PositionFix("r3", 100, 18_522_000, 73_850_000, 50)
        )
        val noBridgePlan = TopologyEngine.computePlan(
            participants, widePositions, dualP2pCapableIds = emptySet(), currentHostId = "r1"
        )
        assertFalse(noBridgePlan.isChainMode)
        assertEquals(TopologyRole.STAR_GO, noBridgePlan.roles[noBridgePlan.starGoId])
    }

    @Test
    fun goAnnounce_beatsTieBreaksDeterministically() {
        val tagA = ByteArray(8) { 0x01 }
        val tagB = ByteArray(8) { 0x02 }
        val ep1Idx1 = MeshControlExtension.GoAnnounce(electionEpoch = 1, electedGoTag = tagA, electedRosterIdx = 1)
        val ep2Idx1 = MeshControlExtension.GoAnnounce(electionEpoch = 2, electedGoTag = tagB, electedRosterIdx = 1)
        val ep2Idx0 = MeshControlExtension.GoAnnounce(electionEpoch = 2, electedGoTag = tagB, electedRosterIdx = 0)
        val ep2Idx0LowTag = MeshControlExtension.GoAnnounce(electionEpoch = 2, electedGoTag = tagA, electedRosterIdx = 0)

        assertTrue(ep2Idx1.beats(ep1Idx1))
        assertTrue(ep2Idx0.beats(ep2Idx1))
        assertTrue(ep2Idx0LowTag.beats(ep2Idx0))
        assertFalse(ep2Idx0.beats(ep2Idx0LowTag))
    }
}

package com.ridervoice.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioRouteDecisionEngineTest {

    private lateinit var engine: AudioRouteDecisionEngine
    private val btAirPods = CandidateDevice.Bluetooth(devId = 101, devName = "AirPods Pro")
    private val wiredHeadset = CandidateDevice.Wired(devId = 202, devName = "Wired Headset")
    private val speaker = CandidateDevice.Speaker(devId = 999, devName = "Speaker")

    @Before
    fun setUp() {
        engine = AudioRouteDecisionEngine()
    }

    @Test
    fun `confirms only when communicationDevice id matches`() {
        val decision = engine.startRouting(listOf(btAirPods, wiredHeadset, speaker))
        assertEquals(RoutingState.PENDING_CONFIRMATION, decision.state)
        assertEquals(101, decision.activeDevice.id)

        // Attempt confirming with wrong ID (e.g. 555)
        val wrongConfirm = engine.confirm(555)
        assertEquals(RoutingState.PENDING_CONFIRMATION, wrongConfirm.state)
        assertFalse(wrongConfirm.requiresRepublish)

        // Confirm with exact matching ID (101)
        val matchConfirm = engine.confirm(101)
        assertEquals(RoutingState.CONFIRMED, matchConfirm.state)
        assertEquals(101, matchConfirm.activeDevice.id)
        assertEquals("AirPods Pro", matchConfirm.activeDevice.name)
    }

    @Test
    fun `times out to the next candidate`() {
        engine.startRouting(listOf(btAirPods, wiredHeadset, speaker))

        // Initial target is AirPods
        assertEquals(101, engine.activeDevice.id)

        // Timeout on AirPods -> moves to Wired Headset
        val timeout1 = engine.onTimeout()
        assertEquals(RoutingState.PENDING_CONFIRMATION, timeout1.state)
        assertEquals(202, timeout1.activeDevice.id)
        assertEquals("Wired Headset", timeout1.activeDevice.name)

        // Timeout on Wired -> moves to Speaker
        val timeout2 = engine.onTimeout()
        assertEquals(RoutingState.PENDING_CONFIRMATION, timeout2.state)
        assertEquals(999, timeout2.activeDevice.id)
        assertEquals("Speaker", timeout2.activeDevice.name)
    }

    @Test
    fun `loudspeaker fallback is labeled Speaker and never Earpiece`() {
        engine.startRouting(listOf(btAirPods))

        // First timeout exhausts candidates
        val fallbackDecision = engine.onTimeout()

        assertEquals(RoutingState.FALLBACK, fallbackDecision.state)
        assertEquals("Speaker", fallbackDecision.activeDevice.name)
        assertNotEquals("Earpiece", fallbackDecision.activeDevice.name)
        assertTrue(fallbackDecision.activeDevice is CandidateDevice.Speaker)
    }

    @Test
    fun `late confirmation triggers a republish request`() {
        engine.startRouting(listOf(btAirPods, speaker))

        // Times out to fallback Speaker
        engine.onTimeout()
        engine.onTimeout()
        assertEquals(RoutingState.FALLBACK, engine.state)
        assertEquals("Speaker", engine.activeDevice.name)

        // Now Bluetooth finally finishes hardware SCO carrier handshake and sends confirmation for 101!
        val lateConfirm = engine.confirm(101)

        assertEquals(RoutingState.CONFIRMED, lateConfirm.state)
        assertEquals(101, lateConfirm.activeDevice.id)
        assertEquals("AirPods Pro", lateConfirm.activeDevice.name)
        assertTrue("Late confirmation must trigger republish request", lateConfirm.requiresRepublish)
    }
}

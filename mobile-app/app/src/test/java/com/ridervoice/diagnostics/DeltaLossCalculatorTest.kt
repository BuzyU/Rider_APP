package com.ridervoice.diagnostics

import com.ridervoice.diagnostics.collector.DeltaLossCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeltaLossCalculatorTest {

    private lateinit var calculator: DeltaLossCalculator

    @Before
    fun setUp() {
        calculator = DeltaLossCalculator()
    }

    @Test
    fun `first sample establishes baseline and returns null`() {
        val loss = calculator.calculateIntervalLoss("track_1", currentReceived = 100L, currentLost = 2L)
        assertNull("First sample must establish baseline without calculating loss", loss)
    }

    @Test
    fun `standard interval calculates correct packet loss percentage`() {
        // Baseline: 100 rx, 0 lost
        calculator.calculateIntervalLoss("track_1", currentReceived = 100L, currentLost = 0L)

        // Next interval: +100 rx (200 total), +10 lost (10 total)
        // Expected loss: 10 / (100 + 10) = 10 / 110 = 9.0909%
        val loss = calculator.calculateIntervalLoss("track_1", currentReceived = 200L, currentLost = 10L)
        assertNotNull(loss)
        assertEquals(9.0909, loss!!, 0.001)
    }

    @Test
    fun `zero loss calculates exactly 0 percent`() {
        calculator.calculateIntervalLoss("track_1", 100L, 5L)
        val loss = calculator.calculateIntervalLoss("track_1", 200L, 5L)
        assertNotNull(loss)
        assertEquals(0.0, loss!!, 0.0001)
    }

    @Test
    fun `negative packetsLost delta is clamped to zero percent`() {
        // WebRTC packetsLost can occasionally decrease due to late out-of-order packet arrival
        calculator.calculateIntervalLoss("track_1", currentReceived = 100L, currentLost = 10L)

        // Cumulative lost decreased from 10 to 8: deltaLost = -2
        val loss = calculator.calculateIntervalLoss("track_1", currentReceived = 200L, currentLost = 8L)
        assertNotNull(loss)
        assertEquals("Negative delta packets lost must be clamped to 0.0%", 0.0, loss!!, 0.0001)
    }

    @Test
    fun `counter reset on reconnect returns null and re-establishes baseline`() {
        calculator.calculateIntervalLoss("track_1", currentReceived = 500L, currentLost = 20L)

        // Next sample has lower packetsReceived (connection restarted or track recreated)
        val resetLoss = calculator.calculateIntervalLoss("track_1", currentReceived = 50L, currentLost = 1L)
        assertNull("Counter reset must return null and establish new baseline", resetLoss)

        // Subsequent sample from new baseline calculates correctly
        val nextLoss = calculator.calculateIntervalLoss("track_1", currentReceived = 150L, currentLost = 11L)
        assertNotNull(nextLoss)
        // deltaRx = 100, deltaLost = 10 -> 10 / 110 = 9.0909%
        assertEquals(9.0909, nextLoss!!, 0.001)
    }

    @Test
    fun `multiple tracks maintain isolated baselines`() {
        calculator.calculateIntervalLoss("rider_alice", 100L, 0L)
        calculator.calculateIntervalLoss("rider_bob", 200L, 0L)

        val lossAlice = calculator.calculateIntervalLoss("rider_alice", 200L, 0L)
        val lossBob = calculator.calculateIntervalLoss("rider_bob", 300L, 20L)

        assertEquals(0.0, lossAlice!!, 0.0001)
        assertEquals(16.6666, lossBob!!, 0.001) // 20 / 120
    }
}

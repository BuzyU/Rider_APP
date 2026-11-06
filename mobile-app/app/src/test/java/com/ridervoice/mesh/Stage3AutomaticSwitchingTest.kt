package com.ridervoice.mesh

import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 Automatic Online/Offline Switching Verification Suite:
 * - Failure confirmation delay (10s)
 * - Temporary glitch suppression
 * - No fallback if no mesh route exists (FALLBACK_UNAVAILABLE)
 * - Recovery stability window (8s)
 * - Instability during recovery aborts handback
 * - Minimum dwell time (20s)
 * - Flapping suppression (max 3 transitions per 2 minutes)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Stage3AutomaticSwitchingTest {

    private val healthyOnline = LocalHealthSignals(
        isValidatedInternet = true,
        isLiveKitConnected = true,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 0,
        isHealthProbeSuccessful = true,
        hasValidMeshRoute = true,
        isMeshTransportAlive = true
    )

    private val failingOnlineWithMesh = LocalHealthSignals(
        isValidatedInternet = false,
        isLiveKitConnected = false,
        isLiveKitReconnecting = true,
        liveKitReconnectAttempts = 3,
        isHealthProbeSuccessful = false,
        hasValidMeshRoute = true,
        isMeshTransportAlive = true
    )

    private val failingOnlineNoMesh = LocalHealthSignals(
        isValidatedInternet = false,
        isLiveKitConnected = false,
        isLiveKitReconnecting = true,
        liveKitReconnectAttempts = 3,
        isHealthProbeSuccessful = false,
        hasValidMeshRoute = false,
        isMeshTransportAlive = true
    )

    @Test
    fun `short network interruption does not trigger fallback`() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)

        // Interruption begins
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // 5 seconds pass (less than 10s failure confirmation delay)
        now += 5_000L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // Connection recovers before 10s window expires
        now += 2_000L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun `confirmed online failure falls back to mesh if route exists`() = runTest {
        var now = 200_000L
        var offlineHandoffCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = {
                offlineHandoffCalled = true
                true
            }
        )

        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // Advance past 10s confirmation window
        now += 10_001L
        sm.evaluate(failingOnlineWithMesh)

        assertTrue(offlineHandoffCalled)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun `confirmed failure transitions to FALLBACK_UNAVAILABLE when no mesh route exists`() = runTest {
        var now = 300_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )

        sm.evaluate(failingOnlineNoMesh)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 10_001L
        sm.evaluate(failingOnlineNoMesh)

        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        // Later when a mesh route appears, transitions to OFFLINE_ACTIVE
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun `recovery requires 8-second stability window and respects 20-second dwell time`() = runTest {
        var now = 400_000L
        var onlineHandoffCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                onlineHandoffCalled = true
                true
            }
        )

        // Trigger fallback to mesh
        sm.evaluate(failingOnlineWithMesh)
        now += 10_001L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Online recovers immediately at 10s (inside 20s minimum dwell window) -> ignored
        now += 10_000L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Dwell time expires (20s since switch) -> enters ONLINE_RECOVERING
        now += 10_001L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // 4 seconds into recovery window (less than 8s) -> remains in recovering
        now += 4_000L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)
        assertFalse(onlineHandoffCalled)

        // Full 8s satisfied -> hands back to LiveKit and enters ONLINE
        now += 4_001L
        sm.evaluate(healthyOnline)
        assertTrue(onlineHandoffCalled)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun `instability during recovery window aborts handback and returns to mesh`() = runTest {
        var now = 500_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = { true }
        )

        sm.evaluate(failingOnlineWithMesh)
        now += 10_001L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Advance past dwell time and enter recovery
        now += 21_000L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // Network drops again after 3 seconds -> aborts back to OFFLINE_ACTIVE
        now += 3_000L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun `anti-flapping limits transitions to max 3 per 2 minutes`() = runTest {
        var now = 600_000L
        val config = AntiFlappingConfig(
            failureConfirmationMs = 10_000L,
            recoveryStabilityMs = 8_000L,
            minDwellMs = 5_000L, // shortened for test
            maxTransitionsPerWindow = 3,
            windowMs = 120_000L
        )
        val sm = AutomaticSwitchStateMachine(
            config = config,
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = { true }
        )

        // Switch 1: ONLINE -> OFFLINE
        sm.evaluate(failingOnlineWithMesh)
        now += 10_001L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Switch 2: OFFLINE -> ONLINE
        now += 6_000L
        sm.evaluate(healthyOnline)
        now += 8_001L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)

        // Switch 3: ONLINE -> OFFLINE
        sm.evaluate(failingOnlineWithMesh)
        now += 10_001L
        sm.evaluate(failingOnlineWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Switch 4 attempt within 2 minutes is suppressed by flapping rate limit
        now += 6_000L
        sm.evaluate(healthyOnline)
        now += 8_001L
        sm.evaluate(healthyOnline)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }
}


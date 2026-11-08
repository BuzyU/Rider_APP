package com.ridervoice.mesh.audit

import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.Clock
import com.ridervoice.mesh.switching.LocalHealthSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DeadMeshHandbackBackoffTest {

    private val deadMeshRecoveredOnline = LocalHealthSignals(
        isValidatedInternet = true,
        isLiveKitConnected = true,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 0,
        isHealthProbeSuccessful = true,
        hasValidMeshRoute = false,
        offlineAvailable = true,
        isMeshTransportAlive = false // Mesh transport is dead!
    )

    @Test
    fun deadMesh_recoveringOnline_backsOffAndHonorsFlappingSuppression() = runTest {
        var now = 10_000L
        val attemptTimes = mutableListOf<Long>()

        val sm = AutomaticSwitchStateMachine(
            clock = Clock { now },
            config = AntiFlappingConfig(
                recoveryStabilityMs = 10_000L,
                minDwellMs = 10_000L,
                maxTransitionsPerWindow = 3,
                windowMs = 120_000L
            ),
            isGateAllowed = { true },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                attemptTimes.add(now)
                false // simulate recoverToLiveKit() always failing
            }
        )

        sm.beginSession("test-dead-mesh-session", now)

        // Handoff to offline first so state is OFFLINE_ACTIVE
        val offlineSignals = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        now += 15_000L
        sm.evaluate(offlineSignals) // ONLINE -> ONLINE_SUSPECT
        now += 10_000L
        sm.evaluate(offlineSignals) // ONLINE_SUSPECT -> OFFLINE_ACTIVE
        assertEquals("Must be in OFFLINE_ACTIVE", AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Now mesh is dead and online is recovered
        // Evaluate every 1s (1_000 ms) for 120s
        val startWindowMs = now
        val windowDurationMs = 120_000L
        var ticks = 0

        while (now - startWindowMs <= windowDurationMs) {
            sm.evaluate(deadMeshRecoveredOnline)
            now += 1_000L
            ticks++
        }

        val attempts = attemptTimes.size
        assertTrue(
            "Attempts must not fire on every 1s tick (got $attempts attempts across $ticks ticks)",
            attempts < ticks / 2
        )
        assertTrue(
            "Attempts ($attempts) must be bounded by maxTransitionsPerWindow (<= 4)",
            attempts <= 4
        )
        assertTrue("Must attempt at least once", attempts >= 1)

        // Verify exponential spacing between successive attempts
        if (attempts >= 2) {
            val gap1 = attemptTimes[1] - attemptTimes[0]
            assertTrue("First gap must be at least recoveryStabilityMs (10s), was $gap1", gap1 >= 10_000L)
        }
        if (attempts >= 3) {
            val gap2 = attemptTimes[2] - attemptTimes[1]
            assertTrue("Second gap must be larger due to doubling backoff, was $gap2", gap2 >= 20_000L)
        }
    }
}


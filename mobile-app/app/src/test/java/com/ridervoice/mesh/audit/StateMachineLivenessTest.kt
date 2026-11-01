package com.ridervoice.mesh.audit

import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineLivenessTest {

    @Test
    fun d4_handoffFalseMustNotLeaveStarting() = runTest {
        var now = 100_000L
        var handoffShouldFail = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { !handoffShouldFail }
        )
        // Move to FALLBACK_UNAVAILABLE
        val noMeshSig = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = false)
        sm.evaluate(noMeshSig)
        now += 10_500L
        sm.evaluate(noMeshSig)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        // Route appears, but handoff returns false
        handoffShouldFail = true
        val withMeshSig = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = true)
        sm.evaluate(withMeshSig)
        assertNotEquals("Should not be stuck in STARTING_OFFLINE_MESH", AutoSwitchState.STARTING_OFFLINE_MESH, sm.state.value)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
    }

    @Test
    fun d4_handoffThrowsMustNotLeaveStarting() = runTest {
        var now = 100_000L
        var shouldThrow = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { if (shouldThrow) error("boom") else true }
        )
        val noMeshSig = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = false)
        sm.evaluate(noMeshSig)
        now += 10_500L
        sm.evaluate(noMeshSig)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        shouldThrow = true
        val withMeshSig = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = true)
        sm.evaluate(withMeshSig)
        assertNotEquals("Should not be stuck in STARTING_OFFLINE_MESH", AutoSwitchState.STARTING_OFFLINE_MESH, sm.state.value)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
    }

    @Test
    fun d4_handbackThrowsMustNotStrandHandingBack() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = { error("LiveKit connection failure") }
        )
        // Transition to OFFLINE_ACTIVE
        val failing = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = true)
        sm.evaluate(failing)
        now += 10_500L
        sm.evaluate(failing)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Past dwell, online healthy signals arrive
        now += 25_000L
        val recovered = LocalHealthSignals(true, true, false, 0, true, hasValidMeshRoute = true)
        sm.evaluate(recovered)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // Past stability window: handoff throws
        now += 8_500L
        sm.evaluate(recovered)
        assertNotEquals("Must not be stranded in HANDING_BACK_TO_LIVEKIT", AutoSwitchState.HANDING_BACK_TO_LIVEKIT, sm.state.value)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun recoveryMustBeReachableWhenLiveKitWasDisconnectedByTheHandoff() = runTest {
        var now = 100_000L
        var liveKitReconnected = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                liveKitReconnected = true
                true
            }
        )
        val failing = LocalHealthSignals(false, false, false, 0, false, hasValidMeshRoute = true)
        sm.evaluate(failing)
        now += 10_500L
        sm.evaluate(failing)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // LiveKit is DISCONNECTED (isLiveKitConnected = false), but Internet & health probe are OK!
        now += 25_000L
        val internetBackSignal = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true
        )
        sm.evaluate(internetBackSignal)
        assertEquals("Should enter ONLINE_RECOVERING when internet & probe are recoverable", AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        now += 8_500L
        sm.evaluate(internetBackSignal)
        assertTrue(liveKitReconnected)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }
}


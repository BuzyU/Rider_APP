package com.ridervoice.mesh

import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.switching.NetCaps
import com.ridervoice.mesh.switching.NetworkCapsReader
import com.ridervoice.mesh.switching.ValidatedInternetSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ValidatedInternetAndStandingRulesTest {

    @Test
    fun wifiConnectedButNotValidated_isNotValidated() {
        val s = ValidatedInternetSource {
            NetCaps(hasInternet = true, isValidated = false, isWifi = true, isCellular = false)
        }
        assertFalse(s.isValidated())
    }

    @Test
    fun cellularValidated_isValidated() {
        val s = ValidatedInternetSource {
            NetCaps(hasInternet = true, isValidated = true, isWifi = false, isCellular = true)
        }
        assertTrue(s.isValidated())
    }

    @Test
    fun noNetwork_isNotValidated() {
        val s = ValidatedInternetSource { null }
        assertFalse(s.isValidated())
    }

    @Test
    fun securityException_isNotValidated() {
        val s = ValidatedInternetSource { throw SecurityException("denied by sandbox") }
        assertFalse(s.isValidated())
    }

    @Test
    fun degradedOnly_neverTotalLoss() {
        val sig = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = false,
            isMeshTransportAlive = true
        )
        assertFalse(sig.isTotalLoss())
        assertFalse(sig.isOnlineFailing())
    }

    @Test
    fun urlBuilder_withAndWithoutTrailingSlash() {
        assertEquals("https://api.example.com/health", LocalHealthSignalsProvider.buildHealthUrl("https://api.example.com/"))
        assertEquals("https://api.example.com/health", LocalHealthSignalsProvider.buildHealthUrl("https://api.example.com"))
    }

    @Test
    fun partialRecovery_resetsSuspectTimer() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // At 5s, internet returns partially (e.g. validated internet back, probe ok, LiveKit reconnecting)
        now += 5_000L
        val partial = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 1,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(partial)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun internetBackLiveKitReconnecting_staysOnline() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        val partial = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 6_000L
        sm.evaluate(partial)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)

        now += 6_000L
        sm.evaluate(partial)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun totalLossFor9s_thenHealthy_noFallback() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        val healthy = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 9_000L
        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 500L
        sm.evaluate(healthy)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun totalLossFor10s_fallsBack() = runTest {
        var now = 100_000L
        var handoffCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { handoffCalled = true; true }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 10_001L
        sm.evaluate(down)
        assertTrue(handoffCalled)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
    }

    @Test
    fun fallbackUnavailable_clearsWhenInternetReturns() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val noMeshTotalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = false,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        val internetBack = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 1,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        sm.evaluate(noMeshTotalLoss)
        now += 10_001L
        sm.evaluate(noMeshTotalLoss)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        now += 1_000L
        sm.evaluate(internetBack)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun packetLossAndRttAlone_neverSuspect() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val degraded = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = false,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        sm.evaluate(degraded)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }
}

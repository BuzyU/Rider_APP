package com.ridervoice.mesh.switching

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class FailedHandoffBackoffTest {

    private val totalLoss = LocalHealthSignals(
        isValidatedInternet = false,
        isLiveKitConnected = false,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 3,
        isHealthProbeSuccessful = false,
        hasValidMeshRoute = true,
        offlineAvailable = true,
        isMeshTransportAlive = true
    )

    private val healthyOnline = LocalHealthSignals(
        isValidatedInternet = true,
        isLiveKitConnected = true,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 0,
        isHealthProbeSuccessful = true,
        hasValidMeshRoute = true,
        offlineAvailable = true,
        isMeshTransportAlive = true
    )

    @Test
    fun failedHandoff_backsOffAndSuppressesFlapping_atMostFourAttemptsInTwoMinutes() = runTest {
        var now = 10_000L
        val attempts = AtomicInteger(0)

        val sm = AutomaticSwitchStateMachine(
            clock = Clock { now },
            config = AntiFlappingConfig(
                failureConfirmationMs = 10_000L,
                offlineRetryBaseMs = 10_000L,
                maxTransitionsPerWindow = 3,
                windowMs = 120_000L
            ),
            isGateAllowed = { true },
            onHandoffToOffline = {
                attempts.incrementAndGet()
                false // always fail
            }
        )

        sm.beginSession("test-ride", now)

        // Advance 120s in 500ms steps, continually evaluating total loss
        val startMs = now
        while (now - startMs <= 120_000L) {
            sm.evaluate(totalLoss)
            now += 500L
        }

        // Must have attempted at least once, but capped at <= 4 attempts (not spinning ~240 times)
        val finalAttempts = attempts.get()
        assertTrue("Expected at least 1 attempt, but got $finalAttempts", finalAttempts >= 1)
        assertTrue("Expected <= 4 attempts in 120s due to exponential backoff and flapping suppression, but got $finalAttempts", finalAttempts <= 4)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
    }

    @Test
    fun failedHandoff_multiplierResetsWhenTotalLossClears() = runTest {
        var now = 10_000L
        val attempts = AtomicInteger(0)

        val sm = AutomaticSwitchStateMachine(
            clock = Clock { now },
            config = AntiFlappingConfig(
                failureConfirmationMs = 10_000L,
                offlineRetryBaseMs = 10_000L,
                maxTransitionsPerWindow = 5,
                windowMs = 120_000L
            ),
            isGateAllowed = { true },
            onHandoffToOffline = {
                attempts.incrementAndGet()
                false // fails
            }
        )

        sm.beginSession("test-ride", now)

        // First attempt happens at now = 20_000L (after 10s suspect)
        sm.evaluate(totalLoss)
        now += 10_000L
        sm.evaluate(totalLoss)
        assertEquals(1, attempts.get())
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        // Total loss clears
        now += 2_000L
        sm.evaluate(healthyOnline)
        assertEquals("Should recover to ONLINE when total loss clears", AutoSwitchState.ONLINE, sm.state.value)

        // Total loss re-occurs: multiplier was reset to 1, so suspect takes base confirmation time (10s)
        now += 5_000L
        sm.evaluate(totalLoss)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        now += 10_000L
        sm.evaluate(totalLoss)
        assertEquals(2, attempts.get())
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
    }

    @Test
    fun failedHandoff_realDispatcherConcurrencyTest() = runBlocking(Dispatchers.Default) {
        val attempts = AtomicInteger(0)
        var virtualTime = 100_000L

        val sm = AutomaticSwitchStateMachine(
            clock = Clock { synchronized(this@FailedHandoffBackoffTest) { virtualTime } },
            config = AntiFlappingConfig(
                failureConfirmationMs = 1_000L,
                offlineRetryBaseMs = 1_000L,
                maxTransitionsPerWindow = 3,
                windowMs = 10_000L
            ),
            isGateAllowed = { true },
            onHandoffToOffline = {
                attempts.incrementAndGet()
                false
            }
        )

        sm.beginSession("concurrency-ride", virtualTime)

        // Launch concurrent evaluators on real Default threadpool
        val jobs = (1..4).map {
            launch(Dispatchers.Default) {
                for (step in 1..20) {
                    synchronized(this@FailedHandoffBackoffTest) {
                        virtualTime += 200L
                    }
                    sm.evaluate(totalLoss)
                }
            }
        }
        jobs.forEach { it.join() }

        // State machine must remain coherent in FALLBACK_UNAVAILABLE without crash or race
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)
        assertTrue(attempts.get() in 1..4)
    }
}


package com.ridervoice.mesh.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class MicControllerOrderingTest {

    @Test
    fun multiThreadedOrderingAndConcurrencyTest() = runBlocking {
        val inFlight = AtomicInteger(0)
        val concurrentViolation = AtomicBoolean(false)
        val lastApplied = AtomicBoolean(false)

        val scope = CoroutineScope(Dispatchers.Default + Job())

        val applyCapture: suspend (Boolean) -> Unit = { want ->
            val count = inFlight.incrementAndGet()
            if (count > 1) {
                concurrentViolation.set(true)
            }
            // Small simulated HAL delay
            var sum = 0
            for (i in 0 until 1000) {
                sum += i
            }
            lastApplied.set(want)
            inFlight.decrementAndGet()
        }

        var audioEnabled = true
        val controller = MicControllerImpl(
            audioEnabled = { audioEnabled },
            applyCapture = applyCapture,
            scope = scope,
            initialVoxEnabled = false
        )

        val threadPool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val iterations = 10_000

        val jobs = (0 until 4).map { threadId ->
            scope.launch(threadPool) {
                val rng = Random(threadId * 1000L)
                for (i in 0 until iterations / 4) {
                    when (rng.nextInt(5)) {
                        0 -> controller.setMicToggled(rng.nextBoolean())
                        1 -> controller.toggleMic()
                        2 -> controller.setPttPressed(rng.nextBoolean())
                        3 -> controller.setVoxEnabled(rng.nextBoolean())
                        4 -> controller.setVoxArmed(rng.nextBoolean())
                    }
                }
            }
        }

        jobs.joinAll()
        delay(200)

        assertFalse(
            "applyCapture was running concurrently with itself!",
            concurrentViolation.get()
        )

        val expectedCaptureWanted = MicRules.captureWanted(
            MicInputs(
                audioEnabled = audioEnabled,
                micToggleOn = controller.micToggleOn.value,
                pttHeld = controller.pttHeld.value,
                voxEnabled = controller.voxEnabled.value,
                voxArmed = controller.voxArmed.value
            )
        )

        assertEquals(
            "Final applied capture state must match MicRules.captureWanted(final inputs)",
            expectedCaptureWanted,
            lastApplied.get()
        )

        scope.cancel()
        threadPool.close()
    }
}


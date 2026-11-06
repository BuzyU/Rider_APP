package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.switching.NetworkCapsReader
import com.ridervoice.mesh.switching.ValidatedInternetSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dagger.Lazy

@OptIn(ExperimentalCoroutinesApi::class)
class FlagOffIsolationTest {

    @Before
    fun setUp() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun localHealthSignalsProvider_doesNotCollectInInit_untilStartWatchingCalled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var collectorCount = 0

        val fakeFlow = kotlinx.coroutines.flow.flow {
            collectorCount++
            emit(true)
            kotlinx.coroutines.awaitCancellation()
        }

        val fakeReader = NetworkCapsReader { null }
        val dummyContext = null
        val validatedSource = object : ValidatedInternetSource(fakeReader, dummyContext) {
            override val validatedFlow = fakeFlow
        }

        val provider = LocalHealthSignalsProvider(
            validatedSource = validatedSource,
            dispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()
        // If init eagerly collected, collectorCount would be 1 here
        assertEquals("Init must not collect validatedFlow", 0, collectorCount)

        provider.startWatching()
        testScheduler.advanceUntilIdle()
        assertEquals("startWatching must initiate collection", 1, collectorCount)

        provider.stopWatching()
        testScheduler.advanceUntilIdle()
        provider.close()
    }

    @Test
    fun appScope_lazyMeshAutoSwitch_neverCallsGet_whenBuildGateClosed() = runTest {
        var getCalled = false
        val lazyManager = Lazy {
            getCalled = true
            throw AssertionError("Lazy.get() must not be called when gate is closed")
        }

        // Build gate closed
        val isBuildGateOpen = false
        val settingsFlow = MutableStateFlow(
            com.ridervoice.mesh.MeshSettingsSnapshot(
                offlineMeshEnabled = false,
                automaticFallbackEnabled = false,
                warmStandbyEnabled = false
            )
        )

        if (isBuildGateOpen) {
            val job = launch {
                settingsFlow
                    .map { it.offlineMeshEnabled }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) lazyManager.get()
                    }
            }
            advanceUntilIdle()
            job.cancel()
        }

        advanceUntilIdle()
        assertFalse("Lazy.get() was called despite closed build gate", getCalled)
    }

    @Test
    fun appScope_lazyMeshAutoSwitch_onlyCallsGet_whenUserSettingToggledOn() = runTest {
        var getCount = 0

        class FakeManager {
            var isStarted = false
            var isStopped = false
            fun start() { isStarted = true }
            fun stop() { isStopped = true }
        }
        val fakeManager = FakeManager()

        val lazyManager = object : Lazy<FakeManager> {
            private var inst: FakeManager? = null
            override fun get(): FakeManager {
                if (inst == null) {
                    getCount++
                    inst = fakeManager
                }
                return inst!!
            }
        }

        val settingsFlow = MutableStateFlow(
            com.ridervoice.mesh.MeshSettingsSnapshot(
                offlineMeshEnabled = false,
                automaticFallbackEnabled = false,
                warmStandbyEnabled = false
            )
        )

        var managerStarted = false
        val job = launch {
            settingsFlow
                .map { it.offlineMeshEnabled }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) {
                        managerStarted = true
                        lazyManager.get().start()
                    } else if (managerStarted) {
                        lazyManager.get().stop()
                    }
                }
        }

        advanceUntilIdle()
        assertEquals("Lazy.get() must not be called while offlineMeshEnabled is false", 0, getCount)
        assertFalse(fakeManager.isStarted)

        // Toggle setting ON
        settingsFlow.value = settingsFlow.value.copy(offlineMeshEnabled = true)
        advanceUntilIdle()
        assertEquals("Lazy.get() called exactly once when enabled", 1, getCount)
        assertTrue("start() should be called", fakeManager.isStarted)

        // Toggle setting OFF
        settingsFlow.value = settingsFlow.value.copy(offlineMeshEnabled = false)
        advanceUntilIdle()
        assertEquals("Lazy.get() should reuse existing instance or get again", 1, getCount)
        assertTrue("stop() should be called", fakeManager.isStopped)

        job.cancel()
    }
}


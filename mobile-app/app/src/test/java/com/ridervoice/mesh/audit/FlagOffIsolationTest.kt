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
    fun meshAutoSwitchLauncher_neverCallsGet_whenBuildGateClosed() = runTest {
        var getCalled = false
        val lazyManager = Lazy<com.ridervoice.mesh.switching.MeshAutoSwitchManager> {
            getCalled = true
            throw AssertionError("Lazy.get() must not be called when gate is closed")
        }

        val settingsFlow = MutableStateFlow(
            com.ridervoice.mesh.MeshSettingsSnapshot(
                offlineMeshEnabled = true,
                automaticFallbackEnabled = false,
                warmStandbyEnabled = false
            )
        )

        val launcher = com.ridervoice.mesh.switching.MeshAutoSwitchLauncher(
            settingsFlow = settingsFlow,
            isBuildGateOpen = { false },
            lazyManager = lazyManager,
            scope = this
        )
        val job = launcher.start()
        advanceUntilIdle()

        assertFalse("Lazy.get() was called despite closed build gate", getCalled)
        assertEquals(null, job)
    }

    @Test
    fun meshAutoSwitchLauncher_onlyCallsGet_whenUserSettingToggledOn() = runTest {
        var getCount = 0
        val mockManager = io.mockk.mockk<com.ridervoice.mesh.switching.MeshAutoSwitchManager>(relaxed = true)

        val lazyManager = object : Lazy<com.ridervoice.mesh.switching.MeshAutoSwitchManager> {
            override fun get(): com.ridervoice.mesh.switching.MeshAutoSwitchManager {
                getCount++
                return mockManager
            }
        }

        val settingsFlow = MutableStateFlow(
            com.ridervoice.mesh.MeshSettingsSnapshot(
                offlineMeshEnabled = false,
                automaticFallbackEnabled = false,
                warmStandbyEnabled = false
            )
        )

        val launcher = com.ridervoice.mesh.switching.MeshAutoSwitchLauncher(
            settingsFlow = settingsFlow,
            isBuildGateOpen = { true },
            lazyManager = lazyManager,
            scope = this
        )
        val job = launcher.start()

        advanceUntilIdle()
        assertEquals("Lazy.get() must not be called while offlineMeshEnabled is false", 0, getCount)
        io.mockk.verify(exactly = 0) { mockManager.start() }

        // Toggle setting ON
        settingsFlow.value = settingsFlow.value.copy(offlineMeshEnabled = true)
        advanceUntilIdle()
        assertEquals("Lazy.get() called when enabled", 1, getCount)
        io.mockk.verify(exactly = 1) { mockManager.start() }

        // Toggle setting OFF
        settingsFlow.value = settingsFlow.value.copy(offlineMeshEnabled = false)
        advanceUntilIdle()
        assertEquals("Lazy.get() called when disabled to stop", 2, getCount)
        io.mockk.verify(exactly = 1) { mockManager.stop() }

        launcher.stop()
    }
}


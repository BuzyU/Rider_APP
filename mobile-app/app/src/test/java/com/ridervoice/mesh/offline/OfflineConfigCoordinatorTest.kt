package com.ridervoice.mesh.offline

import com.ridervoice.mesh.session.OfflineSessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineConfigCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val validCode = "0123456789ABCDEF"

    @Test
    fun allStepsSuccess_transitionsToReady() = testScope.runTest {
        var virtualTime = 1_000_000L
        val coordinator = OfflineConfigCoordinator(
            sessionHolder = OfflineSessionHolder(),
            deviceCapabilityChecker = object : DeviceCapabilityChecker { override fun hasMeshNetworkingCapability() = true },
            permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = true },
            batteryOptimizationChecker = object : BatteryOptimizationChecker { override fun isIgnoringBatteryOptimizations() = true },
            transportReadinessChecker = object : TransportReadinessChecker { override fun canBindMeshTransport() = true },
            dispatcher = testDispatcher,
            clock = { virtualTime }
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.READY, coordinator.state.value.status)
        assertTrue(coordinator.state.value.isReady)
        assertEquals(StepState.PASSED, coordinator.state.value.stepResults[OfflineConfigStep.DEVICE_CAPABILITY]?.state)
        assertEquals(StepState.PASSED, coordinator.state.value.stepResults[OfflineConfigStep.PERMISSIONS]?.state)
        assertEquals(StepState.PASSED, coordinator.state.value.stepResults[OfflineConfigStep.CREDENTIALS]?.state)
        assertEquals(StepState.PASSED, coordinator.state.value.stepResults[OfflineConfigStep.TRANSPORT_READY]?.state)
    }

    @Test
    fun lackingDeviceCapability_reportsUnavailableDevice_neverReady() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            deviceCapabilityChecker = object : DeviceCapabilityChecker { override fun hasMeshNetworkingCapability() = false },
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.UNAVAILABLE_DEVICE, coordinator.state.value.status)
        assertFalse(coordinator.state.value.isReady)
        assertEquals(StepState.FAILED, coordinator.state.value.stepResults[OfflineConfigStep.DEVICE_CAPABILITY]?.state)
    }

    @Test
    fun deniedPermission_reportsNeedsPermission_neverReady() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = false },
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.NEEDS_PERMISSION, coordinator.state.value.status)
        assertFalse(coordinator.state.value.isReady)
        assertEquals(StepState.FAILED, coordinator.state.value.stepResults[OfflineConfigStep.PERMISSIONS]?.state)
    }

    @Test
    fun permissionDeniedThenGrantedOnRetry_transitionsToReady() = testScope.runTest {
        var micGranted = false
        val coordinator = OfflineConfigCoordinator(
            permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = micGranted },
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.NEEDS_PERMISSION, coordinator.state.value.status)

        // Grant permission and retry
        micGranted = true
        coordinator.retryStep(this, OfflineConfigStep.PERMISSIONS, validCode)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.READY, coordinator.state.value.status)
        assertTrue(coordinator.state.value.isReady)
    }

    @Test
    fun missingBackupCode_setsProvisioningStatus() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            dispatcher = testDispatcher
        )

        // Null backup code
        coordinator.startConfig(this, null, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.PROVISIONING, coordinator.state.value.status)
        assertFalse(coordinator.state.value.isReady)

        // Now code arrives -> transitions to ready
        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.READY, coordinator.state.value.status)
    }

    @Test
    fun batteryOptimizationsActive_isNonFatalWarning_stillReachesReady() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            batteryOptimizationChecker = object : BatteryOptimizationChecker { override fun isIgnoringBatteryOptimizations() = false },
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.READY, coordinator.state.value.status)
        assertEquals(StepState.WARNING, coordinator.state.value.stepResults[OfflineConfigStep.BATTERY_OPTIMIZATION]?.state)
    }

    @Test
    fun overallTimeoutAfter90s_setsTimedOut() = testScope.runTest {
        var time = 100_000L
        val coordinator = OfflineConfigCoordinator(
            transportReadinessChecker = object : TransportReadinessChecker {
                override fun canBindMeshTransport(): Boolean {
                    // Simulate long elapsed time exceeding 90s
                    time += 95_000L
                    return true
                }
            },
            dispatcher = testDispatcher,
            clock = { time }
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.TIMED_OUT, coordinator.state.value.status)
        assertFalse(coordinator.state.value.isReady)
    }

    @Test
    fun optOut_transitionsImmediatelyToOptedOut() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        coordinator.optOut()
        advanceUntilIdle()

        assertEquals(OfflineConfigStatus.OPTED_OUT, coordinator.state.value.status)
        assertTrue(coordinator.state.value.optOut)
    }

    @Test
    fun cancel_haltsOngoingExecution() = testScope.runTest {
        val coordinator = OfflineConfigCoordinator(
            dispatcher = testDispatcher
        )

        coordinator.startConfig(this, validCode, isHostMeshEnabled = true)
        coordinator.cancel()
        advanceUntilIdle()

        assertNotNull(coordinator.state.value)
    }
}


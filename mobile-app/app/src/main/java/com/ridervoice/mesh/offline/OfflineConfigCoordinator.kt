package com.ridervoice.mesh.offline

import com.ridervoice.R
import com.ridervoice.mesh.session.OfflineSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.NetworkInterface

interface DeviceCapabilityChecker {
    fun hasMeshNetworkingCapability(): Boolean
}

interface PermissionChecker {
    fun hasRecordAudioPermission(): Boolean
}

interface BatteryOptimizationChecker {
    fun isIgnoringBatteryOptimizations(): Boolean
}

interface TransportReadinessChecker {
    fun canBindMeshTransport(): Boolean
}

class DefaultDeviceCapabilityChecker : DeviceCapabilityChecker {
    override fun hasMeshNetworkingCapability(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            interfaces != null && interfaces.hasMoreElements()
        } catch (_: Exception) {
            false
        }
    }
}

class DefaultPermissionChecker(private val checkAudio: () -> Boolean = { true }) : PermissionChecker {
    override fun hasRecordAudioPermission(): Boolean = checkAudio()
}

class DefaultBatteryOptimizationChecker(private val isIgnoring: () -> Boolean = { true }) : BatteryOptimizationChecker {
    override fun isIgnoringBatteryOptimizations(): Boolean = isIgnoring()
}

class DefaultTransportReadinessChecker : TransportReadinessChecker {
    override fun canBindMeshTransport(): Boolean {
        return try {
            val socket = DatagramSocket(0)
            val bound = socket.isBound
            socket.close()
            bound
        } catch (_: Exception) {
            false
        }
    }
}

class OfflineConfigCoordinator(
    private val sessionHolder: OfflineSessionHolder = OfflineSessionHolder(),
    private val deviceCapabilityChecker: DeviceCapabilityChecker = DefaultDeviceCapabilityChecker(),
    private val permissionChecker: PermissionChecker = DefaultPermissionChecker(),
    private val batteryOptimizationChecker: BatteryOptimizationChecker = DefaultBatteryOptimizationChecker(),
    private val transportReadinessChecker: TransportReadinessChecker = DefaultTransportReadinessChecker(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val STEP_TIMEOUT_MS = 20_000L
        const val OVERALL_TIMEOUT_MS = 90_000L
    }

    private val _state = MutableStateFlow(OfflineConfigState())
    val state: StateFlow<OfflineConfigState> = _state.asStateFlow()

    private var executionJob: Job? = null
    private var configStartTimeMs: Long = 0L

    fun startConfig(
        scope: CoroutineScope,
        backupCode: String?,
        isHostMeshEnabled: Boolean = true,
        isOptedOut: Boolean = false
    ) {
        if (!isHostMeshEnabled) {
            executionJob?.cancel()
            _state.value = OfflineConfigState(
                status = OfflineConfigStatus.OPTED_OUT,
                optOut = true
            )
            return
        }

        if (isOptedOut) {
            optOut()
            return
        }

        executionJob?.cancel()
        executionJob = scope.launch(dispatcher) {
            runConfigurationSequence(backupCode)
        }
    }

    private suspend fun runConfigurationSequence(backupCode: String?) {
        configStartTimeMs = clock()
        val currentSteps = _state.value.stepResults.toMutableMap()

        _state.value = _state.value.copy(
            status = OfflineConfigStatus.CHECKING,
            optOut = false,
            failureReason = null
        )

        // Step 1: DEVICE_CAPABILITY
        val capResult = executeWithStepTimeout(OfflineConfigStep.DEVICE_CAPABILITY) {
            if (deviceCapabilityChecker.hasMeshNetworkingCapability()) {
                StepResult(OfflineConfigStep.DEVICE_CAPABILITY, StepState.PASSED)
            } else {
                StepResult(OfflineConfigStep.DEVICE_CAPABILITY, StepState.FAILED, "Device lacks network interface capability")
            }
        }
        currentSteps[OfflineConfigStep.DEVICE_CAPABILITY] = capResult
        if (capResult.state == StepState.FAILED) {
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.UNAVAILABLE_DEVICE,
                stepResults = currentSteps,
                failureReason = capResult.detail
            )
            return
        }

        // Step 2: PERMISSIONS
        val permResult = executeWithStepTimeout(OfflineConfigStep.PERMISSIONS) {
            if (permissionChecker.hasRecordAudioPermission()) {
                StepResult(OfflineConfigStep.PERMISSIONS, StepState.PASSED)
            } else {
                StepResult(OfflineConfigStep.PERMISSIONS, StepState.FAILED, "RECORD_AUDIO permission required")
            }
        }
        currentSteps[OfflineConfigStep.PERMISSIONS] = permResult
        if (permResult.state == StepState.FAILED) {
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.NEEDS_PERMISSION,
                stepResults = currentSteps,
                failureReason = permResult.detail
            )
            return
        }

        // Step 3: BATTERY_OPTIMIZATION (non-fatal)
        val battResult = executeWithStepTimeout(OfflineConfigStep.BATTERY_OPTIMIZATION) {
            if (batteryOptimizationChecker.isIgnoringBatteryOptimizations()) {
                StepResult(OfflineConfigStep.BATTERY_OPTIMIZATION, StepState.PASSED)
            } else {
                StepResult(
                    OfflineConfigStep.BATTERY_OPTIMIZATION,
                    StepState.WARNING,
                    "Battery optimizations active (non-fatal)"
                )
            }
        }
        currentSteps[OfflineConfigStep.BATTERY_OPTIMIZATION] = battResult

        // Step 4: CREDENTIALS (backup code check and session provision)
        if (backupCode.isNullOrBlank() || backupCode.length != 16) {
            currentSteps[OfflineConfigStep.CREDENTIALS] = StepResult(
                OfflineConfigStep.CREDENTIALS,
                StepState.CHECKING,
                "Waiting for offline backup code from server"
            )
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.PROVISIONING,
                stepResults = currentSteps
            )
            return
        }

        val credResult = executeWithStepTimeout(OfflineConfigStep.CREDENTIALS) {
            val provisioned = sessionHolder.provisionFromBackupCode(backupCode)
            val session = sessionHolder.getBackup()
            if (provisioned && session != null && !session.isExpired(clock())) {
                StepResult(OfflineConfigStep.CREDENTIALS, StepState.PASSED)
            } else {
                StepResult(OfflineConfigStep.CREDENTIALS, StepState.FAILED, "Failed to provision valid offline session from code")
            }
        }
        currentSteps[OfflineConfigStep.CREDENTIALS] = credResult
        if (credResult.state == StepState.FAILED) {
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.FAILED,
                stepResults = currentSteps,
                failureReason = credResult.detail
            )
            return
        }

        // Step 5: TRANSPORT_READY
        val transResult = executeWithStepTimeout(OfflineConfigStep.TRANSPORT_READY) {
            if (transportReadinessChecker.canBindMeshTransport()) {
                StepResult(
                    OfflineConfigStep.TRANSPORT_READY,
                    StepState.PASSED,
                    localizedHintRes = R.string.lobby_offline_hotspot_hint
                )
            } else {
                StepResult(
                    OfflineConfigStep.TRANSPORT_READY,
                    StepState.FAILED,
                    "Unable to bind local UDP mesh link",
                    localizedHintRes = R.string.lobby_offline_hotspot_hint
                )
            }
        }
        currentSteps[OfflineConfigStep.TRANSPORT_READY] = transResult
        if (transResult.state == StepState.FAILED) {
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.FAILED,
                stepResults = currentSteps,
                failureReason = transResult.detail
            )
            return
        }

        // Check overall timeout
        if ((clock() - configStartTimeMs) > OVERALL_TIMEOUT_MS) {
            _state.value = _state.value.copy(
                status = OfflineConfigStatus.TIMED_OUT,
                stepResults = currentSteps,
                failureReason = "Configuration exceeded 90s timeout"
            )
            return
        }

        // All steps verified!
        _state.value = _state.value.copy(
            status = OfflineConfigStatus.READY,
            stepResults = currentSteps,
            failureReason = null
        )
    }

    private suspend fun executeWithStepTimeout(
        step: OfflineConfigStep,
        block: suspend () -> StepResult
    ): StepResult {
        val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            block()
        }
        return result ?: StepResult(step, StepState.FAILED, "Step timed out after 20s")
    }

    fun optOut() {
        executionJob?.cancel()
        _state.value = _state.value.copy(
            status = OfflineConfigStatus.OPTED_OUT,
            optOut = true,
            failureReason = null
        )
    }

    fun optIn(scope: CoroutineScope, backupCode: String?) {
        _state.value = _state.value.copy(
            status = OfflineConfigStatus.CHECKING,
            optOut = false,
            failureReason = null
        )
        startConfig(scope, backupCode, isHostMeshEnabled = true, isOptedOut = false)
    }

    fun retryStep(scope: CoroutineScope, step: OfflineConfigStep, backupCode: String?) {
        startConfig(scope, backupCode, isHostMeshEnabled = true, isOptedOut = false)
    }

    fun cancel() {
        executionJob?.cancel()
    }

    fun restoreState(savedState: OfflineConfigState) {
        _state.value = savedState
    }
}

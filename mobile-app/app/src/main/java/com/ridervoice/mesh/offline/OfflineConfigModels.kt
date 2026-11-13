package com.ridervoice.mesh.offline

import com.ridervoice.R

enum class OfflineConfigStep {
    DEVICE_CAPABILITY,
    PERMISSIONS,
    BATTERY_OPTIMIZATION,
    CREDENTIALS,
    TRANSPORT_READY
}

enum class StepState {
    NOT_CHECKED,
    CHECKING,
    PASSED,
    WARNING,
    FAILED
}

data class StepResult(
    val step: OfflineConfigStep,
    val state: StepState,
    val detail: String? = null,
    val localizedHintRes: Int? = null
)

enum class OfflineConfigStatus(val wireName: String) {
    NOT_STARTED("NOT_STARTED"),
    CHECKING("CHECKING"),
    NEEDS_PERMISSION("NEEDS_PERMISSION"),
    PROVISIONING("PROVISIONING"),
    READY("READY"),
    UNAVAILABLE_DEVICE("UNAVAILABLE_DEVICE"),
    OPTED_OUT("OPTED_OUT"),
    FAILED("FAILED"),
    TIMED_OUT("TIMED_OUT");

    fun isTerminal(): Boolean = this in setOf(
        READY,
        UNAVAILABLE_DEVICE,
        OPTED_OUT,
        FAILED,
        TIMED_OUT
    )
}

data class OfflineConfigState(
    val status: OfflineConfigStatus = OfflineConfigStatus.NOT_STARTED,
    val stepResults: Map<OfflineConfigStep, StepResult> = emptyMap(),
    val optOut: Boolean = false,
    val failureReason: String? = null
) {
    val isReady: Boolean get() = status == OfflineConfigStatus.READY
    val isTerminal: Boolean get() = status.isTerminal()
}


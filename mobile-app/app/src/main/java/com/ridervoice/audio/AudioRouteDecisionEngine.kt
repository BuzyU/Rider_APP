package com.ridervoice.audio

sealed class CandidateDevice(val id: Int, val name: String) {
    data class Bluetooth(val devId: Int, val devName: String = "Bluetooth") : CandidateDevice(devId, devName)
    data class Wired(val devId: Int, val devName: String = "Wired Headset") : CandidateDevice(devId, devName)
    data class Speaker(val devId: Int = 999, val devName: String = "Speaker") : CandidateDevice(devId, devName)
}

enum class RoutingState {
    IDLE,
    PENDING_CONFIRMATION,
    CONFIRMED,
    FALLBACK
}

data class RoutingDecision(
    val state: RoutingState,
    val activeDevice: CandidateDevice,
    val requiresRepublish: Boolean = false,
    val note: String = ""
)

/**
 * Pure decision engine for audio routing confirmation, timeout sequencing, and fallback handling.
 * Enforces:
 * 1. Confirms only when communicationDevice ID matches the target candidate.
 * 2. Times out to the next candidate in sequence.
 * 3. Loudspeaker fallback is labeled "Speaker" (never "Earpiece").
 * 4. Late confirmation of a previously requested device triggers a republish request.
 */
class AudioRouteDecisionEngine(
    private val fallbackSpeaker: CandidateDevice.Speaker = CandidateDevice.Speaker(999, "Speaker")
) {
    private var candidates: List<CandidateDevice> = emptyList()
    private var currentIndex: Int = 0
    private var targetDevice: CandidateDevice? = null
    private val requestedCandidates = mutableMapOf<Int, CandidateDevice>()
    private var currentState: RoutingState = RoutingState.IDLE
    private var currentActiveDevice: CandidateDevice = fallbackSpeaker

    val state: RoutingState get() = currentState
    val activeDevice: CandidateDevice get() = currentActiveDevice

    fun startRouting(candidateList: List<CandidateDevice>): RoutingDecision {
        candidates = candidateList
        currentIndex = 0
        requestedCandidates.clear()
        if (candidates.isEmpty()) {
            currentState = RoutingState.FALLBACK
            targetDevice = null
            currentActiveDevice = fallbackSpeaker
            return RoutingDecision(
                state = currentState,
                activeDevice = currentActiveDevice,
                requiresRepublish = false,
                note = "No candidates available, fallback to Speaker"
            )
        }

        targetDevice = candidates[0]
        currentActiveDevice = targetDevice!!
        requestedCandidates[targetDevice!!.id] = targetDevice!!
        currentState = RoutingState.PENDING_CONFIRMATION
        return RoutingDecision(
            state = currentState,
            activeDevice = currentActiveDevice,
            requiresRepublish = false,
            note = "Attempting candidate ${targetDevice!!.name} (id=${targetDevice!!.id})"
        )
    }

    /**
     * Confirms the routed device.
     * Rule 1: Confirms only when communicationDevice id matches targetDevice.id.
     * Rule 4: Late confirmation triggers a republish request.
     */
    fun confirm(activeDeviceId: Int): RoutingDecision {
        if (currentState == RoutingState.PENDING_CONFIRMATION && targetDevice != null) {
            if (activeDeviceId == targetDevice!!.id) {
                currentState = RoutingState.CONFIRMED
                currentActiveDevice = targetDevice!!
                return RoutingDecision(
                    state = currentState,
                    activeDevice = currentActiveDevice,
                    requiresRepublish = false,
                    note = "Confirmed ${currentActiveDevice.name}"
                )
            } else {
                return RoutingDecision(
                    state = currentState,
                    activeDevice = currentActiveDevice,
                    requiresRepublish = false,
                    note = "Mismatched device ID ($activeDeviceId != ${targetDevice!!.id}), remains pending"
                )
            }
        }

        // Late confirmation: if confirmed device was one of our previously requested candidates
        // but confirmation arrived after we had timed out or moved on
        if (requestedCandidates.containsKey(activeDeviceId) && activeDeviceId != currentActiveDevice.id) {
            currentActiveDevice = requestedCandidates[activeDeviceId]!!
            targetDevice = null
            currentState = RoutingState.CONFIRMED
            return RoutingDecision(
                state = currentState,
                activeDevice = currentActiveDevice,
                requiresRepublish = true, // Late confirmation triggers republish
                note = "Late confirmation for ${currentActiveDevice.name}, republish required"
            )
        }

        return RoutingDecision(
            state = currentState,
            activeDevice = currentActiveDevice,
            requiresRepublish = false,
            note = "Ignored confirmation for id=$activeDeviceId in state $currentState"
        )
    }

    /**
     * Handles timeout when target device does not confirm in time.
     * Rule 2: Times out to the next candidate.
     * Rule 3: Loudspeaker fallback is labeled Speaker (never Earpiece).
     */
    fun onTimeout(): RoutingDecision {
        if (currentState != RoutingState.PENDING_CONFIRMATION) {
            return RoutingDecision(
                state = currentState,
                activeDevice = currentActiveDevice,
                requiresRepublish = false,
                note = "No action on timeout when not pending"
            )
        }

        currentIndex++
        if (currentIndex < candidates.size) {
            targetDevice = candidates[currentIndex]
            currentActiveDevice = targetDevice!!
            requestedCandidates[targetDevice!!.id] = targetDevice!!
            currentState = RoutingState.PENDING_CONFIRMATION
            return RoutingDecision(
                state = currentState,
                activeDevice = currentActiveDevice,
                requiresRepublish = false,
                note = "Timed out, advancing to candidate ${targetDevice!!.name} (id=${targetDevice!!.id})"
            )
        } else {
            currentState = RoutingState.FALLBACK
            targetDevice = null
            currentActiveDevice = fallbackSpeaker
            return RoutingDecision(
                state = currentState,
                activeDevice = currentActiveDevice,
                requiresRepublish = false,
                note = "All candidates timed out, fallback to Speaker"
            )
        }
    }
}

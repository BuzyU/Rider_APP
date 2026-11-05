package com.ridervoice.mesh.audio

import com.ridervoice.audio.HardwarePTTManager

interface HardwarePttPort {
    var onMicToggleRequest: ((Boolean) -> Unit)?
    val isSessionActive: Boolean
        get() = false
    fun activateSession()
    fun deactivateSession()
}

class AndroidHardwarePttPort(
    private val manager: HardwarePTTManager
) : HardwarePttPort {
    override var onMicToggleRequest: ((Boolean) -> Unit)?
        get() = manager.onMicToggleRequest
        set(value) { manager.onMicToggleRequest = value }

    override val isSessionActive: Boolean
        get() = manager.isSessionActive()

    override fun activateSession() {
        manager.activateSession()
    }

    override fun deactivateSession() {
        manager.deactivateSession()
    }
}



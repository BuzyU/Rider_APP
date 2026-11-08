package com.ridervoice.mesh.switching

import com.ridervoice.mesh.MeshSettingsSnapshot
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MeshAutoSwitchLauncher(
    private val settingsFlow: StateFlow<MeshSettingsSnapshot>,
    private val isBuildGateOpen: () -> Boolean,
    private val lazyManager: Lazy<MeshAutoSwitchManager>,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    @Volatile private var started = false

    fun start(): Job? {
        if (!isBuildGateOpen()) return null
        val j = scope.launch {
            settingsFlow
                .map { it.offlineMeshEnabled }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) {
                        started = true
                        lazyManager.get().start()
                    } else if (started) {
                        lazyManager.get().stop()
                    }
                }
        }
        job = j
        return j
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}


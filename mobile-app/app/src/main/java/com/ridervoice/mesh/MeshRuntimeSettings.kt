package com.ridervoice.mesh

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MeshSettingsSnapshot(
    val offlineMeshEnabled: Boolean,
    val automaticFallbackEnabled: Boolean,
    val warmStandbyEnabled: Boolean
)

interface MeshRuntimeSettings {
    val offlineMeshEnabled: Boolean
    val automaticFallbackEnabled: Boolean
    val warmStandbyEnabled: Boolean
    fun currentSnapshot(): MeshSettingsSnapshot =
        MeshSettingsSnapshot(offlineMeshEnabled, automaticFallbackEnabled, warmStandbyEnabled)

    val settingsFlow: StateFlow<MeshSettingsSnapshot>
        get() = MutableStateFlow(currentSnapshot()).asStateFlow()

    fun setOfflineMeshEnabled(v: Boolean) {}
    fun setAutomaticFallbackEnabled(v: Boolean) {}
    fun setWarmStandbyEnabled(v: Boolean) {}

    object AllOff : MeshRuntimeSettings {
        override val offlineMeshEnabled = false
        override val automaticFallbackEnabled = false
        override val warmStandbyEnabled = false
        override val settingsFlow: StateFlow<MeshSettingsSnapshot> =
            MutableStateFlow(MeshSettingsSnapshot(false, false, false)).asStateFlow()

        override fun setOfflineMeshEnabled(v: Boolean) {}
        override fun setAutomaticFallbackEnabled(v: Boolean) {}
        override fun setWarmStandbyEnabled(v: Boolean) {}
    }
}

class PrefsMeshRuntimeSettings(context: Context) : MeshRuntimeSettings {
    private val p = context.applicationContext.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE)

    private fun readSanitizedSnapshot(): MeshSettingsSnapshot {
        val enabled = p.getBoolean("offline_mesh_enabled", false)
        val auto = enabled && p.getBoolean("auto_fallback_enabled", false)
        val warm = enabled && p.getBoolean("warm_standby_enabled", false)
        return MeshSettingsSnapshot(
            offlineMeshEnabled = enabled,
            automaticFallbackEnabled = auto,
            warmStandbyEnabled = warm
        )
    }

    private val _settingsFlow = MutableStateFlow(readSanitizedSnapshot())
    override val settingsFlow: StateFlow<MeshSettingsSnapshot> = _settingsFlow.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settingsFlow.value = readSanitizedSnapshot()
    }

    init {
        p.registerOnSharedPreferenceChangeListener(listener)
    }

    override val offlineMeshEnabled get() = p.getBoolean("offline_mesh_enabled", false)
    override val automaticFallbackEnabled get() = offlineMeshEnabled && p.getBoolean("auto_fallback_enabled", false)
    override val warmStandbyEnabled get() = offlineMeshEnabled && p.getBoolean("warm_standby_enabled", false)

    override fun setOfflineMeshEnabled(v: Boolean) {
        val editor = p.edit().putBoolean("offline_mesh_enabled", v)
        val newAuto = if (!v) false else automaticFallbackEnabled
        val newWarm = if (!v) false else warmStandbyEnabled
        if (!v) {
            editor.putBoolean("auto_fallback_enabled", false)
            editor.putBoolean("warm_standby_enabled", false)
        }
        editor.apply()
        _settingsFlow.value = MeshSettingsSnapshot(
            offlineMeshEnabled = v,
            automaticFallbackEnabled = newAuto,
            warmStandbyEnabled = newWarm
        )
    }

    override fun setAutomaticFallbackEnabled(v: Boolean) {
        val effective = v && offlineMeshEnabled
        p.edit().putBoolean("auto_fallback_enabled", effective).apply()
        _settingsFlow.value = _settingsFlow.value.copy(automaticFallbackEnabled = effective)
    }

    override fun setWarmStandbyEnabled(v: Boolean) {
        val effective = v && offlineMeshEnabled
        p.edit().putBoolean("warm_standby_enabled", effective).apply()
        _settingsFlow.value = _settingsFlow.value.copy(warmStandbyEnabled = effective)
    }
}

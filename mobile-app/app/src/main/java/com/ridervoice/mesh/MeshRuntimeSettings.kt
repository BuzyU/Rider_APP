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
    val settingsFlow: StateFlow<MeshSettingsSnapshot>
        get() = MutableStateFlow(
            MeshSettingsSnapshot(offlineMeshEnabled, automaticFallbackEnabled, warmStandbyEnabled)
        ).asStateFlow()

    object AllOff : MeshRuntimeSettings {
        override val offlineMeshEnabled = false
        override val automaticFallbackEnabled = false
        override val warmStandbyEnabled = false
        override val settingsFlow: StateFlow<MeshSettingsSnapshot> =
            MutableStateFlow(MeshSettingsSnapshot(false, false, false)).asStateFlow()
    }
}

class PrefsMeshRuntimeSettings(context: Context) : MeshRuntimeSettings {
    private val p = context.applicationContext.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(
        MeshSettingsSnapshot(
            offlineMeshEnabled = p.getBoolean("offline_mesh_enabled", false),
            automaticFallbackEnabled = p.getBoolean("auto_fallback_enabled", false),
            warmStandbyEnabled = p.getBoolean("warm_standby_enabled", false)
        )
    )
    override val settingsFlow: StateFlow<MeshSettingsSnapshot> = _settingsFlow.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settingsFlow.value = MeshSettingsSnapshot(
            offlineMeshEnabled = p.getBoolean("offline_mesh_enabled", false),
            automaticFallbackEnabled = p.getBoolean("auto_fallback_enabled", false),
            warmStandbyEnabled = p.getBoolean("warm_standby_enabled", false)
        )
    }

    init {
        p.registerOnSharedPreferenceChangeListener(listener)
    }

    override val offlineMeshEnabled get() = p.getBoolean("offline_mesh_enabled", false)
    override val automaticFallbackEnabled get() = p.getBoolean("auto_fallback_enabled", false)
    override val warmStandbyEnabled get() = p.getBoolean("warm_standby_enabled", false)

    fun setOfflineMeshEnabled(v: Boolean) {
        p.edit().putBoolean("offline_mesh_enabled", v).apply()
        _settingsFlow.value = _settingsFlow.value.copy(offlineMeshEnabled = v)
    }

    fun setAutomaticFallbackEnabled(v: Boolean) {
        p.edit().putBoolean("auto_fallback_enabled", v).apply()
        _settingsFlow.value = _settingsFlow.value.copy(automaticFallbackEnabled = v)
    }

    fun setWarmStandbyEnabled(v: Boolean) {
        p.edit().putBoolean("warm_standby_enabled", v).apply()
        _settingsFlow.value = _settingsFlow.value.copy(warmStandbyEnabled = v)
    }
}

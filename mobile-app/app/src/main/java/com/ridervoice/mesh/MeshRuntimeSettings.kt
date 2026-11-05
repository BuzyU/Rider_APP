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
        p.edit().putBoolean("auto_fallback_enabled", v).apply()
        _settingsFlow.value = _settingsFlow.value.copy(automaticFallbackEnabled = v)
    }

    override fun setWarmStandbyEnabled(v: Boolean) {
        p.edit().putBoolean("warm_standby_enabled", v).apply()
        _settingsFlow.value = _settingsFlow.value.copy(warmStandbyEnabled = v)
    }
}

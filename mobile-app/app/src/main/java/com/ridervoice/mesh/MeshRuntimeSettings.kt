package com.ridervoice.mesh

import android.content.Context

interface MeshRuntimeSettings {
    val offlineMeshEnabled: Boolean
    val automaticFallbackEnabled: Boolean
    val warmStandbyEnabled: Boolean

    object AllOff : MeshRuntimeSettings {
        override val offlineMeshEnabled = false
        override val automaticFallbackEnabled = false
        override val warmStandbyEnabled = false
    }
}

class PrefsMeshRuntimeSettings(context: Context) : MeshRuntimeSettings {
    private val p = context.applicationContext.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE)
    override val offlineMeshEnabled get() = p.getBoolean("offline_mesh_enabled", false)
    override val automaticFallbackEnabled get() = p.getBoolean("auto_fallback_enabled", false)
    override val warmStandbyEnabled get() = p.getBoolean("warm_standby_enabled", false)
    fun setOfflineMeshEnabled(v: Boolean) = p.edit().putBoolean("offline_mesh_enabled", v).apply()
    fun setAutomaticFallbackEnabled(v: Boolean) = p.edit().putBoolean("auto_fallback_enabled", v).apply()
    fun setWarmStandbyEnabled(v: Boolean) = p.edit().putBoolean("warm_standby_enabled", v).apply()
}


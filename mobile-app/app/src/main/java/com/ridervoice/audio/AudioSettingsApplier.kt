package com.ridervoice.audio

import android.content.SharedPreferences
import com.ridervoice.network.LiveKitManager
import com.ridervoice.security.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2 (F-10): AudioSettingsApplier observes preference changes and applies them
 * live to VoxEngine and LiveKitManager during an active ride (not just once at joinRoom).
 */
@Singleton
class AudioSettingsApplier @Inject constructor(
    private val securePrefs: SecurePreferences,
    private val voxEngine: VoxEngine,
    private val liveKitManager: LiveKitManager
) {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "settings_vox_sensitivity",
            "settings_open_mic",
            "settings_noise_cancellation",
            "settings_hardware_ptt",
            "settings_hybrid_vox" -> applyAll()
        }
    }

    init {
        securePrefs.registerListener(listener)
        applyAll()
    }

    fun applyAll() {
        val sensitivity = when (securePrefs.getString("settings_vox_sensitivity", "Medium")) {
            "Low" -> 0.2f
            "High" -> 0.8f
            else -> 0.5f
        }
        voxEngine.setSensitivity(sensitivity)
        voxEngine.setVoxEnabled(securePrefs.getBoolean("settings_open_mic", true))
        liveKitManager.noiseSuppressionEnabled = securePrefs.getBoolean("settings_noise_cancellation", true)
        val hardwarePttEnabled = securePrefs.getBoolean("settings_hardware_ptt", true)
        val hybridVoxEnabled = securePrefs.getBoolean("settings_hybrid_vox", true)
        voxEngine.setHardwarePttEnabled(hardwarePttEnabled)
        voxEngine.setHybridVoxMode(hybridVoxEnabled)
    }

    fun stop() {
        securePrefs.unregisterListener(listener)
    }
}

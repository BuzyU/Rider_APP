package com.ridervoice.ui.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.ridervoice.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class AppSettingsState(
    val darkTheme: Boolean = false,
    val noiseCancellation: Boolean = true,
    val openMic: Boolean = true,
    val autoHudMode: Boolean = true,
    val gloveMode: Boolean = true,
    val micSensitivity: String = "High",
    val voxSensitivity: String = "Medium",
    val audioOutput: String = "Auto",
    val speedForHud: String = "15 km/h",
    val reconnectMode: String = "Auto"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePrefs: SecurePreferences
) : ViewModel() {

    private val _settingsState = MutableStateFlow(loadInitialState())
    val settingsState: StateFlow<AppSettingsState> = _settingsState.asStateFlow()

    private fun loadInitialState(): AppSettingsState {
        return AppSettingsState(
            darkTheme = securePrefs.getBoolean("dark_theme_enabled", false),
            noiseCancellation = securePrefs.getBoolean("settings_noise_cancellation", true),
            openMic = securePrefs.getBoolean("settings_open_mic", true),
            autoHudMode = securePrefs.getBoolean("settings_auto_hud", true),
            gloveMode = securePrefs.getBoolean("settings_glove_mode", true),
            micSensitivity = securePrefs.getString("settings_mic_sensitivity", "High"),
            voxSensitivity = securePrefs.getString("settings_vox_sensitivity", "Medium"),
            audioOutput = securePrefs.getString("settings_audio_output", "Auto"),
            speedForHud = securePrefs.getString("settings_speed_hud", "15 km/h"),
            reconnectMode = securePrefs.getString("settings_reconnect_mode", "Auto")
        )
    }

    fun toggleDarkTheme() {
        val newValue = !_settingsState.value.darkTheme
        _settingsState.value = _settingsState.value.copy(darkTheme = newValue)
        securePrefs.saveBoolean("dark_theme_enabled", newValue)
        com.ridervoice.ui.theme.ThemeState.set(newValue)
    }

    fun toggleNoiseCancellation() {
        val current = _settingsState.value.noiseCancellation
        _settingsState.value = _settingsState.value.copy(noiseCancellation = !current)
        securePrefs.saveBoolean("settings_noise_cancellation", !current)
    }

    fun toggleOpenMic() {
        val current = _settingsState.value.openMic
        _settingsState.value = _settingsState.value.copy(openMic = !current)
        securePrefs.saveBoolean("settings_open_mic", !current)
    }

    fun toggleAutoHud() {
        val current = _settingsState.value.autoHudMode
        _settingsState.value = _settingsState.value.copy(autoHudMode = !current)
        securePrefs.saveBoolean("settings_auto_hud", !current)
    }

    fun toggleGloveMode() {
        val current = _settingsState.value.gloveMode
        _settingsState.value = _settingsState.value.copy(gloveMode = !current)
        securePrefs.saveBoolean("settings_glove_mode", !current)
    }

    fun updateSettingValue(key: String, value: String) {
        val current = _settingsState.value
        _settingsState.value = when (key) {
            "micSensitivity" -> {
                securePrefs.saveString("settings_mic_sensitivity", value)
                current.copy(micSensitivity = value)
            }
            "voxSensitivity" -> {
                securePrefs.saveString("settings_vox_sensitivity", value)
                current.copy(voxSensitivity = value)
            }
            "audioOutput" -> {
                securePrefs.saveString("settings_audio_output", value)
                current.copy(audioOutput = value)
            }
            "speedForHud" -> {
                securePrefs.saveString("settings_speed_hud", value)
                current.copy(speedForHud = value)
            }
            "reconnectMode" -> {
                securePrefs.saveString("settings_reconnect_mode", value)
                current.copy(reconnectMode = value)
            }
            else -> current
        }
    }
}

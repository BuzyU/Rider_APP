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
    val voxSensitivity: String = "Medium",
    // Headset / PTT settings (migrated from raw headset_prefs → SecurePreferences)
    val hardwarePtt: Boolean = true,
    val hybridVox: Boolean = true,
    val networkDiagnostics: Boolean = false,
    val shareDiagnosticsWhenHostEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePrefs: SecurePreferences,
    private val uploadManager: com.ridervoice.diagnostics.upload.DiagnosticUploadManager
) : ViewModel() {

    private val _settingsState = MutableStateFlow(loadInitialState())
    val settingsState: StateFlow<AppSettingsState> = _settingsState.asStateFlow()

    val diagnosticStatusText: StateFlow<String> = uploadManager.statusText

    private val _showDiagnosticDisclosure = MutableStateFlow(false)
    val showDiagnosticDisclosure: StateFlow<Boolean> = _showDiagnosticDisclosure.asStateFlow()

    private fun loadInitialState(): AppSettingsState {
        return AppSettingsState(
            darkTheme = securePrefs.getBoolean("dark_theme_enabled", false),
            noiseCancellation = securePrefs.getBoolean("settings_noise_cancellation", true),
            openMic = securePrefs.getBoolean("settings_open_mic", true),
            autoHudMode = securePrefs.getBoolean("settings_auto_hud", true),
            gloveMode = securePrefs.getBoolean("settings_glove_mode", true),
            voxSensitivity = securePrefs.getString("settings_vox_sensitivity", "Medium"),
            hardwarePtt = securePrefs.getBoolean("settings_hardware_ptt", true),
            hybridVox = securePrefs.getBoolean("settings_hybrid_vox", true),
            networkDiagnostics = securePrefs.isNetworkDiagnosticsEnabled(),
            shareDiagnosticsWhenHostEnabled = securePrefs.isShareDiagnosticsWhenHostEnabled()
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

    fun toggleHardwarePtt() {
        val current = _settingsState.value.hardwarePtt
        _settingsState.value = _settingsState.value.copy(hardwarePtt = !current)
        securePrefs.saveBoolean("settings_hardware_ptt", !current)
    }

    fun toggleHybridVox() {
        val current = _settingsState.value.hybridVox
        _settingsState.value = _settingsState.value.copy(hybridVox = !current)
        securePrefs.saveBoolean("settings_hybrid_vox", !current)
    }

    fun toggleNetworkDiagnostics() {
        val current = _settingsState.value.networkDiagnostics
        if (!current) {
            val hasSeen = securePrefs.getBoolean("has_seen_diag_disclosure", false)
            if (!hasSeen) {
                _showDiagnosticDisclosure.value = true
                return
            }
            enableDiagnostics()
        } else {
            disableDiagnostics()
        }
    }

    fun confirmDiagnosticDisclosure() {
        securePrefs.saveBoolean("has_seen_diag_disclosure", true)
        _showDiagnosticDisclosure.value = false
        enableDiagnostics()
    }

    fun dismissDiagnosticDisclosure() {
        _showDiagnosticDisclosure.value = false
        disableDiagnostics()
    }

    private fun enableDiagnostics() {
        _settingsState.value = _settingsState.value.copy(networkDiagnostics = true)
        securePrefs.setNetworkDiagnosticsEnabled(true)
    }

    private fun disableDiagnostics() {
        _settingsState.value = _settingsState.value.copy(networkDiagnostics = false)
        securePrefs.setNetworkDiagnosticsEnabled(false)
    }

    fun toggleShareDiagnosticsWhenHostEnabled() {
        val current = _settingsState.value.shareDiagnosticsWhenHostEnabled
        val newValue = !current
        _settingsState.value = _settingsState.value.copy(shareDiagnosticsWhenHostEnabled = newValue)
        securePrefs.setShareDiagnosticsWhenHostEnabled(newValue)
    }

    fun updateSettingValue(key: String, value: String) {
        val current = _settingsState.value
        _settingsState.value = when (key) {
            "voxSensitivity" -> {
                securePrefs.saveString("settings_vox_sensitivity", value)
                current.copy(voxSensitivity = value)
            }
            else -> current
        }
    }
}

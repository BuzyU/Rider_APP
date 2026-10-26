package com.ridervoice.audio

import com.ridervoice.network.LiveKitManager
import com.ridervoice.security.SecurePreferences
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsApplierTest {

    @Test
    fun applyAll_mapsPreferencesToVoxEngineAndLiveKit() {
        val mockPrefs = mockk<SecurePreferences>(relaxed = true)
        val mockVox = mockk<VoxEngine>(relaxed = true)
        val mockLiveKit = mockk<LiveKitManager>(relaxed = true)

        every { mockPrefs.getString("settings_vox_sensitivity", "Medium") } returns "High"
        every { mockPrefs.getBoolean("settings_open_mic", true) } returns false
        every { mockPrefs.getBoolean("settings_noise_cancellation", true) } returns false
        every { mockPrefs.getBoolean("settings_hardware_ptt", true) } returns true
        every { mockPrefs.getBoolean("settings_hybrid_vox", true) } returns false

        val applier = AudioSettingsApplier(mockPrefs, mockVox, mockLiveKit)

        verify { mockVox.setSensitivity(0.8f) }
        verify { mockVox.setVoxEnabled(false) }
        verify { mockVox.setHardwarePttEnabled(true) }
        verify { mockVox.setHybridVoxMode(false) }
        verify { mockLiveKit.noiseSuppressionEnabled = false }

        // Change preference to Low sensitivity and VOX on
        every { mockPrefs.getString("settings_vox_sensitivity", "Medium") } returns "Low"
        every { mockPrefs.getBoolean("settings_open_mic", true) } returns true
        every { mockPrefs.getBoolean("settings_noise_cancellation", true) } returns true

        applier.applyAll()

        verify { mockVox.setSensitivity(0.2f) }
        verify { mockVox.setVoxEnabled(true) }
        verify { mockLiveKit.noiseSuppressionEnabled = true }

        applier.stop()
        verify { mockPrefs.unregisterListener(any<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()) }
    }
}

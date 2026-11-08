package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveKitTransportGateTest {

    private class FakeRuntimeSettings(initial: Boolean = false) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = initial,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )
        override val settingsFlow = _flow.asStateFlow()
        override val offlineMeshEnabled get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(offlineMeshEnabled = v)
        }
        override fun setAutomaticFallbackEnabled(v: Boolean) {}
        override fun setWarmStandbyEnabled(v: Boolean) {}
    }

    @Before
    fun setUp() {
        FeatureFlags.localOverride = true
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun defaultConstructorGateAllowedRespectsOfflineAllowed() {
        val settings = FakeRuntimeSettings(initial = false)
        OfflineMeshGate.install(settings)
        assertFalse(OfflineMeshGate.isOfflineAllowed())

        val transport = LiveKitVoiceTransport(
            liveKitPort = mockk<LiveKitPort>(relaxed = true),
            audioOwnershipArbiter = AudioOwnershipArbiter()
        )

        val gateField = LiveKitVoiceTransport::class.java.getDeclaredField("gateAllowed").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val gateAllowed = gateField.get(transport) as () -> Boolean

        assertFalse("With toggle off, default gateAllowed must return false", gateAllowed())

        settings.setOfflineMeshEnabled(true)
        assertTrue(OfflineMeshGate.isOfflineAllowed())
        assertTrue("With toggle on, default gateAllowed must return true", gateAllowed())
    }
}

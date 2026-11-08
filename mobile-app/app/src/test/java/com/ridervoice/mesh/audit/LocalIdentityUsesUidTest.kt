package com.ridervoice.mesh.audit

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocalIdentityUsesUidTest {

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : com.ridervoice.mesh.MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
            override val settingsFlow = MutableStateFlow(com.ridervoice.mesh.MeshSettingsSnapshot(true, true, true))
        })
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(com.ridervoice.mesh.MeshRuntimeSettings.AllOff)
    }

    @Test
    fun participantsListWithRemoteRiderFirst_doesNotChangeSelfTag() {
        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val connState = MutableStateFlow(ConnectionState.CONNECTED)
        every { lkManager.connectionState } returns connState

        // Remote rider is at index 0 in participants list
        val remoteRider = Participant(identity = "remote-rider-uid", displayName = "Remote Rider", isGhost = false)
        val localRider = Participant(identity = "local-rider-uid", displayName = "Local Rider", isGhost = false)
        every { lkManager.participants } returns MutableStateFlow(listOf(remoteRider, localRider))

        val transport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        var capturedSupplier: (() -> String?)? = null
        every { transport.onlineIdentitySupplier = any() } answers {
            capturedSupplier = firstArg()
        }

        val manager = MeshAutoSwitchManager(
            activeRideRepository = mockk(relaxed = true),
            liveKitManager = lkManager,
            coordinator = mockk(relaxed = true),
            meshTransport = transport,
            healthSignalsProvider = mockk(relaxed = true),
            localOnlineIdentity = { "local-rider-uid" }
        )

        manager.start()

        // When connected, supplier must return localOnlineIdentity ("local-rider-uid"), NEVER the first participant ("remote-rider-uid")
        assertEquals("local-rider-uid", capturedSupplier?.invoke())

        // When disconnected, supplier must return null
        connState.value = ConnectionState.DISCONNECTED
        assertNull("onlineIdentitySupplier must return null when LiveKit is not CONNECTED", capturedSupplier?.invoke())
    }
}


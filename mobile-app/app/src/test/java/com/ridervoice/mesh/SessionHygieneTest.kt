package com.ridervoice.mesh

import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.mesh.ui.OfflineMeshViewModel
import com.ridervoice.navigation.Routes
import com.ridervoice.services.MeshForegroundService
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHygieneTest {

    @Test
    fun offlineSessionHolder_retainsAndClearsSession() {
        OfflineSessionHolder.clear()
        assertNull(OfflineSessionHolder.get())

        val session = OfflineSession.create(1000L)
        OfflineSessionHolder.set(session)
        assertEquals(session, OfflineSessionHolder.get())
        assertEquals(session, OfflineSessionHolder.currentSession)

        OfflineSessionHolder.clear()
        assertNull(OfflineSessionHolder.get())
    }

    @Test
    fun routes_offlineMeshHudRouteContainsNoArguments() {
        assertFalse(
            "OFFLINE_MESH_HUD route must not contain sessionCode argument",
            Routes.OFFLINE_MESH_HUD.contains("{sessionCode}")
        )
        assertEquals("offline_mesh_hud", Routes.OFFLINE_MESH_HUD)

        val pathWithSecret = Routes.offlineMeshHudPath()
        assertEquals("offline_mesh_hud", pathWithSecret)
    }

    @Test
    fun offlineSession_toStringNeverLeaksCodeOrKeyBytes() {
        val session = OfflineSession.create(1000L)
        val text = session.toString()

        assertFalse("toString must not contain pairingCode", text.contains(session.pairingCode))
        assertFalse("toString must not contain key bytes", text.contains(session.authKey().contentToString()))
        assertFalse("toString must not contain payloadKey bytes", text.contains(session.payloadKey().contentToString()))
        assertTrue("toString should contain sessionId", text.contains(session.sessionId))
    }

    @Test
    fun offlineMeshViewModel_onClearedDispatchesTeardownToAppScope() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testAppScope = TestScope(testDispatcher)

        val mockTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()

        val viewModel = OfflineMeshViewModel(
            transport = mockTransport,
            arbiter = arbiter,
            appScope = testAppScope
        )

        val session = OfflineSession.create(1000L)
        OfflineSessionHolder.set(session)

        // Invoke protected onCleared via reflection
        val onClearedMethod = OfflineMeshViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        testScheduler.runCurrent()

        coVerify(exactly = 1) { mockTransport.stop() }
        assertNull("OfflineSessionHolder must be cleared on teardown", OfflineSessionHolder.get())
    }
}


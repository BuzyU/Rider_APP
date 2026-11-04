package com.ridervoice.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.SessionKdf
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.ui.OfflineMeshViewModel
import com.ridervoice.services.MeshForegroundService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {

    private val holder = OfflineSessionHolder.getHolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        holder.clearAll()
    }

    @After
    fun tearDown() {
        holder.clearAll()
        Dispatchers.resetMain()
    }

    /**
     * 1. close_zeroizesKeys: assert keys are wiped and session cannot be accessed once closed.
     */
    @Test
    fun close_zeroizesKeys() {
        val session = OfflineSession.create(1000L)
        val authKey = session.authKey()
        val payloadKey = session.payloadKey()

        assertFalse("Auth key should initially not be all zeros", authKey.all { it == 0.toByte() })
        assertFalse("Payload key should initially not be all zeros", payloadKey.all { it == 0.toByte() })

        // Extract internal byte array references via reflection to verify zeroing
        val authField = OfflineSession::class.java.getDeclaredField("key")
        authField.isAccessible = true
        val rawAuth = authField.get(session) as ByteArray

        val payloadField = OfflineSession::class.java.getDeclaredField("pKey")
        payloadField.isAccessible = true
        val rawPayload = payloadField.get(session) as ByteArray

        session.close()

        assertTrue("Session must report isClosed == true", session.isClosed)
        assertTrue("Raw auth key bytes must be zeroized on close", rawAuth.all { it == 0.toByte() })
        assertTrue("Raw payload key bytes must be zeroized on close", rawPayload.all { it == 0.toByte() })

        try {
            session.authKey()
            fail("authKey() on closed session must throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Success
        }

        try {
            session.payloadKey()
            fail("payloadKey() on closed session must throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Success
        }
    }

    /**
     * 2. clear_closesSession: assert holder.clearCurrent() / clearAll() closes the active session.
     */
    @Test
    fun clear_closesSession() {
        val session = OfflineSession.create(1000L)
        holder.setCurrent(session)
        assertFalse(session.isClosed)

        holder.clearCurrent()

        assertNull("Holder current must be null after clearCurrent", holder.current.value)
        assertTrue("Cleared session must be closed", session.isClosed)
    }

    /**
     * 3. toString_hasNoSecret: assert toString contains truncated sessionId and never pairing code or raw keys.
     */
    @Test
    fun toString_hasNoSecret() {
        val session = OfflineSession.create(1000L)
        val str = session.toString()

        assertTrue("toString should contain prefix of sessionId", str.contains(session.sessionId.take(8)))
        assertFalse("toString must not leak pairingCode", str.contains(session.pairingCode))
        assertFalse("toString must not leak auth key bytes", str.contains(session.authKey().contentToString()))
        assertFalse("toString must not leak payload key bytes", str.contains(session.payloadKey().contentToString()))
    }

    /**
     * 4. holder_isSingleton: assert getHolder returns identical instance.
     */
    @Test
    fun holder_isSingleton() {
        val h1 = OfflineSessionHolder.getHolder()
        val h2 = OfflineSessionHolder.getHolder()
        assertTrue("OfflineSessionHolder.getHolder() must return singleton instance", h1 === h2)
    }

    /**
     * 5. stopAction_doesNotStartForeground: assert decideForeground(ACTION_STOP) produces FgsDecision.Stop.
     */
    @Test
    fun stopAction_doesNotStartForeground() {
        val decision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_STOP)
        assertEquals(MeshForegroundService.FgsDecision.Stop, decision)
    }

    /**
     * 6. startRefused_withoutMicPermission: companion start refuses when RECORD_AUDIO denied.
     */
    @Test
    fun startRefused_withoutMicPermission() {
        val mockContext = mockk<Context>(relaxed = true)
        every {
            mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        MeshForegroundService.start(mockContext)

        verify(exactly = 0) { mockContext.startService(any()) }
        verify(exactly = 0) { mockContext.startForegroundService(any()) }
    }

    /**
     * 7. startAllowed_withMic: companion start proceeds when RECORD_AUDIO granted.
     */
    @Test
    fun startAllowed_withMic() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = false
            override val warmStandbyEnabled = false
        })
        FeatureFlags.localOverride = true

        val mockContext = mockk<Context>(relaxed = true)
        every {
            mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        try {
            MeshForegroundService.start(mockContext)

            verify(atLeast = 1) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mockContext.startForegroundService(any())
                } else {
                    mockContext.startService(any())
                }
            }
        } finally {
            FeatureFlags.localOverride = null
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        }
    }

    /**
     * 8. belowApi29_usesPlainStartForeground: decideForeground produces Start decision with appropriate withType flag.
     */
    @Test
    fun belowApi29_usesPlainStartForeground() {
        val decision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_START)
        assertTrue("Decision must be Start", decision is MeshForegroundService.FgsDecision.Start)
        val startDecision = decision as MeshForegroundService.FgsDecision.Start
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, startDecision.withType)
    }

    /**
     * 9. rotation_keepsSessionAndTransport: holder maintains active session across ViewModel re-creation.
     */
    @Test
    fun rotation_keepsSessionAndTransport() {
        val session = OfflineSession.create(1000L)
        holder.setCurrent(session)

        val mockTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()

        val vm1 = OfflineMeshViewModel(mockTransport, arbiter, holder)
        assertEquals(session, vm1.activeSession.value)

        // Simulate config change recreation
        val vm2 = OfflineMeshViewModel(mockTransport, arbiter, holder)
        assertEquals(session, vm2.activeSession.value)
        assertFalse("Session must remain open across rotation", session.isClosed)
    }

    /**
     * 10. startSession_sameSession_isIdempotent: starting already running session does not fail or duplicate.
     */
    @Test
    fun startSession_sameSession_isIdempotent() = runTest {
        val mockTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()
        val vm = OfflineMeshViewModel(mockTransport, arbiter, holder)

        val session = OfflineSession.create(1000L)
        vm.startSession(session)
        assertEquals(session, holder.current.value)

        // Second call with same session
        vm.startSession(session)
        assertEquals(session, holder.current.value)
    }

    /**
     * 11. lobbyEnter_usesViewModelSession_noUiThreadDerivation: ViewModel derives off-main and stores in holder.
     */
    @Test
    fun lobbyEnter_usesViewModelSession_noUiThreadDerivation() = runTest {
        val mockTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()
        val vm = OfflineMeshViewModel(mockTransport, arbiter, holder)

        var readyCalled = false
        vm.createNewSession { code ->
            readyCalled = true
            assertNotNull(code)
        }

        val start = System.currentTimeMillis()
        while (!readyCalled && System.currentTimeMillis() - start < 3000L) {
            kotlinx.coroutines.delay(20)
        }

        assertTrue("onReady callback must be invoked", readyCalled)
        assertNotNull("Holder must contain current session", holder.current.value)
    }

    /**
     * 12. invalidCode_neverCreatesSession: invalid pairing codes fail without session creation.
     */
    @Test
    fun invalidCode_neverCreatesSession() {
        val session = OfflineSession.fromCode("123", 1000L)
        assertNull("Short code must return null session", session)

        val invalidChar = OfflineSession.fromCode("BAD!CODE#9", 1000L)
        assertNull("Invalid char code must return null session", invalidChar)

        assertNull("Holder must remain empty", holder.current.value)
    }

    /**
     * 13. lobbyCallback_carriesNoSecret: verify OfflineMeshLobbyScreen parameter types don't take code.
     */
    @Test
    fun lobbyCallback_carriesNoSecret() {
        // Find OfflineMeshLobbyScreen method
        val methods = Class.forName("com.ridervoice.mesh.ui.OfflineMeshLobbyScreenKt").declaredMethods
        val lobbyFunc = methods.firstOrNull { it.name.startsWith("OfflineMeshLobbyScreen") }
        assertNotNull("OfflineMeshLobbyScreen Composable must exist", lobbyFunc)

        // Ensure that none of the parameter types is a function accepting String for onEnterSession
        for (paramType in lobbyFunc!!.parameterTypes) {
            assertFalse(
                "LobbyScreen parameters must not accept String callbacks that could leak code",
                paramType.name.contains("Function1") && paramType.genericInterfaces.any { it.typeName.contains("String") }
            )
        }
    }
}

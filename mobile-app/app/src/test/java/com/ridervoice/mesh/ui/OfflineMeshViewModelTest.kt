package com.ridervoice.mesh.ui

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMeshViewModelTest {

    private class FakeMeshLink : MeshLink {
        override val linkId = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun createNewSessionOffloadsPBKDF2AndPopulatesActiveSession() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val link = FakeMeshLink()
        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link }
        )
        val viewModel = OfflineMeshViewModel(transport, arbiter)

        var readyCode: String? = null
        viewModel.createNewSession { code ->
            readyCode = code
        }

        withTimeout(5_000L) {
            while (readyCode == null) {
                delay(20L)
            }
        }

        assertNotNull(readyCode)
        assertEquals(16, readyCode?.length)
        assertNotNull(viewModel.activeSession.value)
        assertEquals(readyCode, viewModel.activeSession.value?.pairingCode)
        assertFalse(viewModel.isDerivingKey.value)
        assertFalse(viewModel.sessionError.value)
    }

    @Test
    fun joinWithInvalidCodeInvokesOnInvalidAndSetsSessionError() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val link = FakeMeshLink()
        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link }
        )
        val viewModel = OfflineMeshViewModel(transport, arbiter)

        var invalidCalled = false
        var readyCalled = false

        viewModel.joinWithCode(
            rawCode = "INVALID_SHORT",
            onReady = { readyCalled = true },
            onInvalid = { invalidCalled = true }
        )

        withTimeout(5_000L) {
            while (!invalidCalled && !readyCalled) {
                delay(20L)
            }
        }

        assertTrue("onInvalid must be invoked for malformed code", invalidCalled)
        assertFalse("onReady must not be invoked for malformed code", readyCalled)
        assertTrue(viewModel.sessionError.value)
        assertNull(viewModel.activeSession.value)
    }

    @Test
    fun resolveAndStartSessionWithValidCodeStartsSession() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val link = FakeMeshLink()
        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link }
        )
        val viewModel = OfflineMeshViewModel(transport, arbiter)

        val realSession = OfflineSession.create(1_000L)
        var invalidCalled = false

        viewModel.resolveAndStartSession(realSession.pairingCode) {
            invalidCalled = true
        }

        withTimeout(5_000L) {
            while (viewModel.activeSession.value == null && !invalidCalled) {
                delay(20L)
            }
        }

        assertFalse("Valid code must not invoke onInvalid", invalidCalled)
        assertNotNull(viewModel.activeSession.value)
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)

        viewModel.stopSession()
        withTimeout(5_000L) {
            while (transport.state.value != VoiceTransportState.STOPPED) {
                delay(20L)
            }
        }
        assertEquals(VoiceTransportState.STOPPED, transport.state.value)
        assertNull(viewModel.activeSession.value)
    }
}

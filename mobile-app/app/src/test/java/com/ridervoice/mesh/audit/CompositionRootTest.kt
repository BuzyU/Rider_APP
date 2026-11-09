package com.ridervoice.mesh.audit

import android.content.Context
import com.ridervoice.di.MeshModule
import com.ridervoice.mesh.identity.InMemoryMeshKeyValueStore
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.link.NoNetworkLocks
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.ui.OfflineMeshViewModel
import com.ridervoice.network.LiveKitManager
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CompositionRootTest {

    /**
     * (a) Unit test: the arbiter instance injected into the LiveKit transport and the mesh transport is the same object.
     * Also asserts that exactly one OfflineMeshVoiceTransport singleton is provided and wired into the coordinator.
     */
    @Test
    fun arbiterInstanceInjectedIntoLiveKitAndMeshIsSameObject() {
        val mockContext = mockk<Context>(relaxed = true)
        val arbiter = MeshModule.provideAudioOwnershipArbiter()
        val locks = MeshModule.provideNetworkLocks(mockContext)
        val identityRepo = MeshIdentityRepository(InMemoryMeshKeyValueStore(), { "TestRider" }, { UUID.randomUUID().toString() })
        val routeController = MeshModule.provideMeshAudioRouteController(mockContext, arbiter)
        val hardwarePttPort = mockk<com.ridervoice.mesh.audio.HardwarePttPort>(relaxed = true)
        val wifiWatcher = MeshModule.provideWifiNetworkWatcher(mockContext)
        val diagnosticsRepo = MeshModule.provideMeshDiagnosticsRepository()
        val meshTransport = MeshModule.provideOfflineMeshVoiceTransport(identityRepo, arbiter, locks, routeController, hardwarePttPort, wifiWatcher, diagnosticsRepo, mockContext)
        val liveKitManager = mockk<LiveKitManager>(relaxed = true)
        val voxEngine = mockk<com.ridervoice.audio.VoxEngine>(relaxed = true)
        val lkTransport = MeshModule.provideLiveKitVoiceTransport(liveKitManager, arbiter, voxEngine, mockk(relaxed = true))
        val secretProvider = MeshModule.provideOfflineSecretProvider(MeshModule.provideOfflineSessionHolder(mockContext))
        val coordinator = MeshModule.provideVoiceSessionCoordinator(lkTransport, meshTransport, arbiter, secretProvider)

        // Single shared arbiter
        assertSame("LiveKitVoiceTransport must share the singleton arbiter", arbiter, lkTransport.audioOwnershipArbiter)
        assertSame("OfflineMeshVoiceTransport must share the singleton arbiter", arbiter, meshTransport.audioOwnershipArbiter)
        assertSame("VoiceSessionCoordinator must share the singleton arbiter", arbiter, coordinator.audioOwnershipArbiter)

        // Single shared mesh transport
        assertSame("Coordinator must receive the singleton mesh transport", meshTransport, coordinator.meshTransport)
    }

    /**
     * (b) Unit with fakes: LiveKit owns the mic, startAudio on mesh is refused.
     */
    @Test
    fun whenLiveKitOwnsMicStartAudioOnMeshIsRefused() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val claimed = arbiter.claimLiveKit()
        assertTrue("LiveKit claims mic", claimed)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        val identityRepo = MeshIdentityRepository(InMemoryMeshKeyValueStore(), { "Rider1" }, { UUID.randomUUID().toString() })
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identityRepo.get() },
            audioOwnershipArbiter = arbiter,
            locks = NoNetworkLocks,
            gateAllowed = { true }
        )

        // 1. Direct arbiter claim attempt is refused
        assertFalse("Mesh claim on arbiter must be refused while LiveKit owns mic", arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // 2. Direct enableAudio attempt is refused
        val session = OfflineSession.create(System.currentTimeMillis())
        try {
            transport.startDiscoveryOnly(session)
            val audioClaimFailed = try {
                val res = transport.enableAudio()
                res !is EnableAudioResult.Success
            } catch (_: IllegalStateException) {
                true
            }
            assertTrue("startAudio/enableAudio on mesh must be refused when LiveKit owns mic", audioClaimFailed)
            assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

            // 3. OfflineMeshViewModel refusal and error emission
            val viewModel = OfflineMeshViewModel(transport, arbiter)
            assertTrue("ViewModel detects LiveKit mic ownership", viewModel.isMicOwnedByLiveKit())
            viewModel.startSession(session)
            assertTrue("Conflict error emitted to prevent concurrent mic capture", viewModel.liveKitConflictError.value)
            assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)
        } finally {
            transport.stop()
        }
    }

    /**
     * (c) Unit test: stopping mesh releases only OFFLINE_MESH, never LiveKit.
     */
    @Test
    fun stoppingMeshReleasesOnlyOfflineMesh() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identityRepo = MeshIdentityRepository(InMemoryMeshKeyValueStore(), { "Rider2" }, { UUID.randomUUID().toString() })
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identityRepo.get() },
            audioOwnershipArbiter = arbiter,
            locks = NoNetworkLocks,
            gateAllowed = { true }
        )

        // Case 1: Mesh owned the mic
        val meshClaimed = arbiter.tryClaimOfflineMesh()
        assertTrue("Mesh claimed mic", meshClaimed)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        transport.stop()
        assertEquals("Stopping mesh must release OFFLINE_MESH", AudioOwner.NONE, arbiter.owner.value)

        // Case 2: LiveKit owns the mic; mesh stop must NOT release LiveKit
        val lkClaimed = arbiter.claimLiveKit()
        assertTrue("LiveKit claimed mic", lkClaimed)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)
        transport.stop()
        assertEquals("Stopping mesh must NOT release LIVEKIT", AudioOwner.LIVEKIT, arbiter.owner.value)
    }

    /**
     * (d) Static test: no production file other than MeshModule calls the constructors
     * for OfflineMeshVoiceTransport, AudioOwnershipArbiter, MeshIdentityRepository, or AndroidNetworkLocks.
     */
    @Test
    fun noProductionFileOtherThanMeshModuleCallsSingleSourceConstructors() {
        val userDir = System.getProperty("user.dir") ?: "."
        val mainSrc = listOf(
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java"),
            File(userDir, "mobile-app/app/src/main/java")
        ).firstOrNull { it.exists() } ?: File("mobile-app/app/src/main/java")

        assertTrue("Main source directory must exist: ${mainSrc.absolutePath}", mainSrc.exists())

        val forbiddenConstructors = listOf(
            "OfflineMeshVoiceTransport(" to "OfflineMeshVoiceTransport.kt",
            "AudioOwnershipArbiter(" to "AudioOwnershipArbiter.kt",
            "MeshIdentityRepository(" to "MeshIdentity.kt",
            "AndroidNetworkLocks(" to "NetworkLocks.kt"
        )

        val violations = mutableListOf<String>()

        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val fileName = file.name
            if (fileName == "MeshModule.kt") return@forEach

            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val cleanLine = line.trim()
                if (cleanLine.startsWith("//") || cleanLine.startsWith("*")) return@forEachIndexed

                for ((constructorCall, definingFile) in forbiddenConstructors) {
                    if (fileName != definingFile && cleanLine.contains(constructorCall)) {
                        violations.add("${file.relativeTo(mainSrc).path}:${index + 1} calls $constructorCall")
                    }
                }
            }
        }

        assertTrue(
            "Found unauthorized constructor calls outside MeshModule:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}

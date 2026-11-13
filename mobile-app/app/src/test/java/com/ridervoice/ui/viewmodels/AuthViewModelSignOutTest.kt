package com.ridervoice.ui.viewmodels

import android.content.Context
import com.ridervoice.mesh.session.NoOpBackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.network.ApiService
import com.ridervoice.network.LiveKitManager
import com.ridervoice.security.AuthRepository
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelSignOutTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockAuthRepo = mockk<AuthRepository>(relaxed = true)
    private val mockApi = mockk<ApiService>(relaxed = true)
    private val mockPrefs = mockk<SecurePreferences>(relaxed = true)
    private val mockLiveKit = mockk<LiveKitManager>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    private lateinit var activeRideRepo: ActiveRideRepository
    private lateinit var sessionHolder: OfflineSessionHolder
    private lateinit var authVm: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        activeRideRepo = ActiveRideRepository(prefs = mockPrefs, api = mockApi)
        sessionHolder = OfflineSessionHolder(
            backupStore = NoOpBackupSessionStore,
            kdfIterations = 1,
            dispatcher = Dispatchers.Unconfined
        )
        authVm = AuthViewModel(
            authRepository = mockAuthRepo,
            apiService = mockApi,
            securePrefs = mockPrefs,
            activeRideRepository = activeRideRepo,
            liveKitManager = mockLiveKit,
            sessionHolder = sessionHolder,
            context = mockContext
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `host signOut without force requires confirmation and halts D13`() = runTest(testDispatcher) {
        // Given an active ride where the user is host
        activeRideRepo.enterLobby(roomId = "room-host", name = "Host Convoy", isHost = true)
        sessionHolder.provisionFromBackupCode("ABCD1234EFGH5678")
        assertNotNull(sessionHolder.getBackup())

        var confirmationCalled = false
        var completedCalled = false

        authVm.signOut(
            forceHostEnd = false,
            onHostConfirmationRequired = { confirmationCalled = true },
            onSignOutComplete = { completedCalled = true }
        )
        advanceUntilIdle()

        assertTrue("Host confirmation callback must be triggered", confirmationCalled)
        assertFalse("Sign out should not complete without confirmation", completedCalled)

        // Verify authRepository.signOut was NOT called
        verify(exactly = 0) { mockAuthRepo.signOut() }
        // Verify active ride not cleared yet
        assertNotNull(activeRideRepo.state.value)
        // Verify session not cleared yet
        assertNotNull(sessionHolder.getBackup())
    }

    @Test
    fun `host signOut with force calls endRide and signs out last`() = runTest(testDispatcher) {
        activeRideRepo.enterLobby(roomId = "room-host-end", name = "Host Convoy", isHost = true)
        sessionHolder.provisionFromBackupCode("ABCD1234EFGH5678")

        coEvery { mockApi.endRideForEveryone("room-host-end") } returns Response.success(Unit as Any)

        var completedCalled = false
        val executionOrder = mutableListOf<String>()

        coEvery { mockApi.endRideForEveryone(any()) } answers {
            executionOrder.add("API_END_RIDE")
            Response.success(Unit as Any)
        }
        every { mockLiveKit.disconnect() } answers {
            executionOrder.add("LIVEKIT_DISCONNECT")
        }
        every { mockAuthRepo.signOut() } answers {
            executionOrder.add("AUTH_SIGN_OUT")
        }

        authVm.signOut(
            forceHostEnd = true,
            onSignOutComplete = { completedCalled = true }
        )
        advanceUntilIdle()

        assertTrue("Sign out should complete", completedCalled)
        assertNull("Active ride must be cleared", activeRideRepo.state.value)
        assertNull("Session must be cleared", sessionHolder.getBackup())
        assertNull("Current session must be cleared", sessionHolder.get())

        // Verify ordering: API call first, authRepository.signOut LAST
        coVerify(exactly = 1) { mockApi.endRideForEveryone("room-host-end") }
        verify(exactly = 1) { mockAuthRepo.signOut() }
        assertEquals("API_END_RIDE", executionOrder.first())
        assertEquals("AUTH_SIGN_OUT", executionOrder.last())
    }

    @Test
    fun `member signOut calls leaveLobby and signs out last`() = runTest(testDispatcher) {
        activeRideRepo.enterLobby(roomId = "room-member", name = "Member Convoy", isHost = false)
        sessionHolder.provisionFromBackupCode("ABCD1234EFGH5678")

        val executionOrder = mutableListOf<String>()
        coEvery { mockApi.leaveLobby(any()) } answers {
            executionOrder.add("API_LEAVE_LOBBY")
            Response.success(Unit as Any)
        }
        every { mockLiveKit.disconnect() } answers {
            executionOrder.add("LIVEKIT_DISCONNECT")
        }
        every { mockAuthRepo.signOut() } answers {
            executionOrder.add("AUTH_SIGN_OUT")
        }

        var completedCalled = false
        authVm.signOut(
            onSignOutComplete = { completedCalled = true }
        )
        advanceUntilIdle()

        assertTrue(completedCalled)
        assertNull("Active ride must be cleared", activeRideRepo.state.value)
        assertNull("Offline session must be cleared", sessionHolder.getBackup())

        coVerify(exactly = 1) { mockApi.leaveLobby("room-member") }
        verify(exactly = 1) { mockAuthRepo.signOut() }
        assertEquals("API_LEAVE_LOBBY", executionOrder.first())
        assertEquals("AUTH_SIGN_OUT", executionOrder.last())
    }

    @Test
    fun `backend error during signOut does not prevent local cleanup and auth signOut`() = runTest(testDispatcher) {
        activeRideRepo.enterLobby(roomId = "room-failing", name = "Convoy", isHost = false)
        sessionHolder.provisionFromBackupCode("ABCD1234EFGH5678")

        coEvery { mockApi.leaveLobby(any()) } throws RuntimeException("Network unreachable")

        var completedCalled = false
        authVm.signOut(
            onSignOutComplete = { completedCalled = true }
        )
        advanceUntilIdle()

        assertTrue("Sign out must complete despite network error", completedCalled)
        assertNull("Active ride must be cleared", activeRideRepo.state.value)
        assertNull("Offline session must be cleared", sessionHolder.getBackup())
        verify(exactly = 1) { mockAuthRepo.signOut() }
    }
}

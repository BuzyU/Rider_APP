package com.ridervoice.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.audio.HardwarePTTManager
import com.ridervoice.audio.VoxEngine
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.PrefsMeshRuntimeSettings
import com.ridervoice.mesh.audio.AndroidAudioManagerPort
import com.ridervoice.mesh.audio.AndroidHardwarePttPort
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshAudioRouteController
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.identity.RiderNameProvider
import com.ridervoice.mesh.identity.SharedPrefsMeshKeyValueStore
import com.ridervoice.mesh.link.AndroidNetworkLocks
import com.ridervoice.mesh.link.AndroidWifiNetworkWatcher
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.link.WifiNetworkWatcher
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.switching.AndroidNetworkCapsReader
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.switching.ValidatedInternetSource
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.ui.MeshApplicationScope
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkObserver
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    fun provideMeshRuntimeSettings(
        @ApplicationContext context: Context
    ): MeshRuntimeSettings = PrefsMeshRuntimeSettings(context)

    @Provides
    @Singleton
    fun provideAudioOwnershipArbiter(): AudioOwnershipArbiter = AudioOwnershipArbiter()

    @Provides
    @Singleton
    fun provideNetworkLocks(
        @ApplicationContext context: Context
    ): NetworkLocks = AndroidNetworkLocks(context)

    @Provides
    @Singleton
    fun provideNetworkObserver(
        @ApplicationContext context: Context
    ): NetworkObserver = NetworkObserver(context)

    @Provides
    @Singleton
    fun provideRiderNameProvider(
        @ApplicationContext context: Context
    ): RiderNameProvider = RiderNameProvider {
        val firebaseName = runCatching {
            FirebaseAuth.getInstance().currentUser?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (firebaseName != null) return@RiderNameProvider firebaseName

        val prefName = runCatching {
            context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
                .getString("display_name", null)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (prefName != null) return@RiderNameProvider prefName

        val emailPrefix = runCatching {
            FirebaseAuth.getInstance().currentUser?.email?.substringBefore("@")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        emailPrefix ?: "Rider"
    }

    @Provides
    @Singleton
    fun provideMeshIdentityRepository(
        @ApplicationContext context: Context,
        riderNameProvider: RiderNameProvider
    ): MeshIdentityRepository = MeshIdentityRepository(
        store = SharedPrefsMeshKeyValueStore(context),
        riderNameProvider = riderNameProvider
    )

    @Provides
    @Singleton
    fun provideHardwarePttPort(
        hardwarePTTManager: HardwarePTTManager
    ): HardwarePttPort = AndroidHardwarePttPort(hardwarePTTManager)

    @Provides
    @Singleton
    fun provideMeshAudioRouteController(
        @ApplicationContext context: Context,
        arbiter: AudioOwnershipArbiter
    ): MeshAudioRouteController = MeshAudioRouteController(
        port = AndroidAudioManagerPort(context),
        arbiter = arbiter
    )

    @Provides
    @Singleton
    @MeshApplicationScope
    fun provideMeshApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideOfflineSessionHolder(
        @ApplicationContext context: Context
    ): OfflineSessionHolder = OfflineSessionHolder(
        backupStore = EncryptedPrefsBackupSessionStore(context),
        restoreAsync = true
    )

    fun provideOfflineSessionHolder(): OfflineSessionHolder = OfflineSessionHolder()

    @Provides
    @Singleton
    fun provideOfflineSecretProvider(
        holder: OfflineSessionHolder
    ): OfflineSecretProvider = HolderOfflineSecretProvider(holder)

    @Provides
    @Singleton
    fun provideLiveKitVoiceTransport(
        liveKitManager: LiveKitManager,
        arbiter: AudioOwnershipArbiter,
        voxEngine: VoxEngine,
        securePreferences: SecurePreferences
    ): LiveKitVoiceTransport = LiveKitVoiceTransport(
        liveKitManager = liveKitManager,
        audioOwnershipArbiter = arbiter,
        voxStopper = { voxEngine.stop() },
        voxRestarter = { voxEngine.start() },
        voxRunningCheck = {
            securePreferences.getBoolean("settings_open_mic", true) &&
                liveKitManager.connectionState.value == ConnectionState.CONNECTED
        }
    )

    fun provideLiveKitVoiceTransport(
        liveKitManager: LiveKitManager,
        arbiter: AudioOwnershipArbiter,
        voxEngine: VoxEngine
    ): LiveKitVoiceTransport = provideLiveKitVoiceTransport(
        liveKitManager = liveKitManager,
        arbiter = arbiter,
        voxEngine = voxEngine,
        securePreferences = SecurePreferences()
    )

    @Provides
    @Singleton
    fun provideWifiNetworkWatcher(
        @ApplicationContext context: Context
    ): WifiNetworkWatcher = AndroidWifiNetworkWatcher(context)

    @Provides
    @Singleton
    fun provideMeshDiagnosticsRepository(): MeshDiagnosticsRepository = MeshDiagnosticsRepository()

    @Provides
    @Singleton
    fun provideOfflineMeshVoiceTransport(
        identityRepo: MeshIdentityRepository,
        arbiter: AudioOwnershipArbiter,
        locks: NetworkLocks,
        routeController: MeshAudioRouteController,
        hardwarePttPort: HardwarePttPort,
        wifiWatcher: WifiNetworkWatcher,
        diagnosticsRepo: MeshDiagnosticsRepository,
        @ApplicationContext context: Context
    ): OfflineMeshVoiceTransport = OfflineMeshVoiceTransport(
        identity = null,
        identityProvider = { identityRepo.get() },
        audioOwnershipArbiter = arbiter,
        locks = locks,
        routeController = routeController,
        hardwarePttPort = hardwarePttPort,
        wifiWatcher = wifiWatcher,
        diagnosticsRepo = diagnosticsRepo,
        linkFactory = {
            UdpMeshLink(
                bindPort = MeshConfig.MESH_PORT,
                gateAllowed = { OfflineMeshGate.isOfflineAllowed() },
                networkBinder = { socket ->
                    locks.bindSocketToWifi(socket)
                },
                onBufferDropped = { diagnosticsRepo.recordBufferDrop() }
            )
        },
        hasMicPermission = {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    @Provides
    @Singleton
    fun provideVoiceSessionCoordinator(
        liveKitTransport: LiveKitVoiceTransport,
        meshTransport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        secretProvider: OfflineSecretProvider
    ): VoiceSessionCoordinator = VoiceSessionCoordinator(
        liveKitTransport = liveKitTransport,
        meshTransport = meshTransport,
        audioOwnershipArbiter = arbiter,
        secretProvider = secretProvider
    )

    @Provides
    @Singleton
    fun provideValidatedInternetSource(
        @ApplicationContext context: Context
    ): ValidatedInternetSource = ValidatedInternetSource(
        reader = AndroidNetworkCapsReader(context),
        context = context
    )

    @Provides
    @Singleton
    fun provideLocalHealthSignalsProvider(
        networkObserver: NetworkObserver,
        liveKitManager: LiveKitManager,
        liveKitVoiceTransport: LiveKitVoiceTransport,
        meshTransport: OfflineMeshVoiceTransport,
        validated: ValidatedInternetSource
    ): LocalHealthSignalsProvider = LocalHealthSignalsProvider(
        networkObserver = networkObserver,
        liveKitConnectionState = liveKitManager.connectionState,
        liveKitReconnectAttempts = { liveKitVoiceTransport.reconnectAttempts.value },
        meshControl = meshTransport,
        isInternetValidated = { validated.isValidated() },
        validatedSource = validated
    )

    @Provides
    @Singleton
    fun provideMeshAutoSwitchManager(
        activeRideRepository: ActiveRideRepository,
        liveKitManager: LiveKitManager,
        coordinator: VoiceSessionCoordinator,
        meshTransport: OfflineMeshVoiceTransport,
        healthSignalsProvider: LocalHealthSignalsProvider,
        secretProvider: OfflineSecretProvider,
        sessionHolder: OfflineSessionHolder
    ): MeshAutoSwitchManager = MeshAutoSwitchManager(
        activeRideRepository = activeRideRepository,
        liveKitManager = liveKitManager,
        coordinator = coordinator,
        meshTransport = meshTransport,
        healthSignalsProvider = healthSignalsProvider,
        secretProvider = secretProvider,
        isStandaloneSessionActive = { sessionHolder.current.value != null }
    )

    @Provides
    @Singleton
    fun provideAutomaticSwitchStateMachine(
        autoSwitchManager: MeshAutoSwitchManager
    ): AutomaticSwitchStateMachine = autoSwitchManager.stateMachine

    @Provides
    @Singleton
    fun provideWifiCapabilityChecker(
        @ApplicationContext context: Context
    ): com.ridervoice.mesh.ui.WifiCapabilityChecker = com.ridervoice.mesh.ui.SystemWifiCapabilityChecker(context)
}

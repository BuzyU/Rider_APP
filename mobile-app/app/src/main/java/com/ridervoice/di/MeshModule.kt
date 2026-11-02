package com.ridervoice.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.audio.VoxEngine
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.PrefsMeshRuntimeSettings
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.identity.SharedPrefsMeshKeyValueStore
import com.ridervoice.mesh.link.AndroidNetworkLocks
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideMeshIdentityRepository(
        @ApplicationContext context: Context
    ): MeshIdentityRepository {
        val initialName = runCatching {
            FirebaseAuth.getInstance().currentUser?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Rider"

        return MeshIdentityRepository(
            store = SharedPrefsMeshKeyValueStore(context),
            defaultDisplayName = { initialName }
        )
    }

    @Provides
    @Singleton
    fun provideLiveKitVoiceTransport(
        liveKitManager: LiveKitManager,
        arbiter: AudioOwnershipArbiter,
        voxEngine: VoxEngine? = null
    ): LiveKitVoiceTransport = LiveKitVoiceTransport(
        liveKitManager = liveKitManager,
        audioOwnershipArbiter = arbiter,
        voxStopper = { voxEngine?.stop() }
    )

    @Provides
    @Singleton
    fun provideOfflineMeshVoiceTransport(
        identityRepo: MeshIdentityRepository,
        arbiter: AudioOwnershipArbiter,
        locks: NetworkLocks,
        @ApplicationContext context: Context? = null
    ): OfflineMeshVoiceTransport = OfflineMeshVoiceTransport(
        identity = identityRepo.get(),
        audioOwnershipArbiter = arbiter,
        locks = locks,
        hasMicPermission = {
            if (context != null) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        }
    )

    @Provides
    @Singleton
    fun provideVoiceSessionCoordinator(
        liveKitTransport: LiveKitVoiceTransport,
        meshTransport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter
    ): VoiceSessionCoordinator = VoiceSessionCoordinator(
        liveKitTransport = liveKitTransport,
        meshTransport = meshTransport,
        audioOwnershipArbiter = arbiter
    )

    @Provides
    @Singleton
    fun provideLocalHealthSignalsProvider(
        networkObserver: NetworkObserver,
        liveKitManager: LiveKitManager,
        meshTransport: OfflineMeshVoiceTransport
    ): LocalHealthSignalsProvider = LocalHealthSignalsProvider(
        networkObserver = networkObserver,
        liveKitConnectionState = liveKitManager.connectionState,
        meshControl = meshTransport
    )
}

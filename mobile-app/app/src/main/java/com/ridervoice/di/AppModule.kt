package com.ridervoice.di

import com.ridervoice.BuildConfig
import com.ridervoice.network.ApiService
import com.ridervoice.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    object AuthTokenCache {
        var cachedToken: String? = null
        var expiryTime: Long = 0L

        fun clear() {
            cachedToken = null
            expiryTime = 0L
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        appEvents: com.ridervoice.state.AppEvents
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (com.ridervoice.BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
        }

        return builder
            .addInterceptor { chain ->

                var token: String? = null
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    val now = System.currentTimeMillis()
                    if (AuthTokenCache.cachedToken != null && AuthTokenCache.expiryTime > now) {
                        token = AuthTokenCache.cachedToken
                    } else {
                        try {
                            val task = com.google.android.gms.tasks.Tasks.await(user.getIdToken(false))
                            token = task.token
                            AuthTokenCache.cachedToken = token
                            AuthTokenCache.expiryTime = now + 45 * 60 * 1000
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                val requestBuilder = chain.request().newBuilder()
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                // Phase 0 – P0.6: X-App-Version header for server-side client identification
                requestBuilder.addHeader("X-App-Version", BuildConfig.VERSION_NAME)

                // V3-6: Generate or propagate X-Request-Id for end-to-end tracing and user support
                val existingReqId = chain.request().header("X-Request-Id")
                val requestId = if (!existingReqId.isNullOrBlank()) existingReqId else java.util.UUID.randomUUID().toString()
                requestBuilder.header("X-Request-Id", requestId)

                // Part I3: X-Country header from EmergencyNumbers
                val country = com.ridervoice.utils.EmergencyNumbers.resolve(context).countryIso ?: "IN"
                requestBuilder.addHeader("X-Country", country)

                val response = chain.proceed(requestBuilder.build())
                if (response.code == 426) {
                    appEvents.emitUpdateRequired()
                }
                response
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideRideDatabase(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): com.ridervoice.data.local.RideDatabase {
        return androidx.room.Room.databaseBuilder(
            context,
            com.ridervoice.data.local.RideDatabase::class.java,
            "ridervoice_flight_recorder.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun provideRideDao(database: com.ridervoice.data.local.RideDatabase): com.ridervoice.data.local.RideDao {
        return database.rideDao()
    }

    @Provides
    @Singleton
    fun provideSecurePreferences(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): com.ridervoice.security.SecurePreferences {
        return com.ridervoice.security.SecurePreferences(context)
    }

    @Provides
    @Singleton
    fun provideAudioManagerGateway(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): com.ridervoice.audio.AudioManagerGateway {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        return com.ridervoice.audio.AndroidAudioManagerGateway(audioManager)
    }
}

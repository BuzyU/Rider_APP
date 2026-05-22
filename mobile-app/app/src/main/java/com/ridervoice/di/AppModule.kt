package com.ridervoice.di

import com.ridervoice.network.ApiService
import com.ridervoice.security.AuthRepository
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

    @Provides
    @Singleton
    fun provideOkHttpClient(authRepository: AuthRepository): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .addInterceptor { chain ->
                // Blocking call in interceptor to fetch Firebase token
                var token: String? = null
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    try {
                        val task = com.google.android.gms.tasks.Tasks.await(user.getIdToken(false))
                        token = task.token
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val requestBuilder = chain.request().newBuilder()
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                
                chain.proceed(requestBuilder.build())
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
}

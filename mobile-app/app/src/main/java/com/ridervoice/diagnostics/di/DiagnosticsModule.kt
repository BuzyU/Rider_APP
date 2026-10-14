package com.ridervoice.diagnostics.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ridervoice.diagnostics.data.DiagnosticsDatabase
import com.ridervoice.diagnostics.data.dao.DiagOutboxDao
import com.ridervoice.diagnostics.data.dao.DiagSampleDao
import com.ridervoice.diagnostics.data.dao.DiagSessionDao
import com.ridervoice.diagnostics.upload.DiagnosticApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }

    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(
        @ApplicationContext context: Context
    ): DiagnosticsDatabase {
        return Room.databaseBuilder(
            context,
            DiagnosticsDatabase::class.java,
            "diagnostics.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideDiagSessionDao(database: DiagnosticsDatabase): DiagSessionDao {
        return database.sessionDao()
    }

    @Provides
    @Singleton
    fun provideDiagSampleDao(database: DiagnosticsDatabase): DiagSampleDao {
        return database.sampleDao()
    }

    @Provides
    @Singleton
    fun provideDiagOutboxDao(database: DiagnosticsDatabase): DiagOutboxDao {
        return database.outboxDao()
    }

    @Provides
    @Singleton
    fun provideDiagnosticApiService(retrofit: Retrofit): DiagnosticApiService {
        return retrofit.create(DiagnosticApiService::class.java)
    }
}

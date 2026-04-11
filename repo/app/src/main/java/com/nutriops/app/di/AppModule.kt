package com.nutriops.app.di

import android.content.Context
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.security.EncryptionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAuditManager(database: NutriOpsDatabase): AuditManager {
        return AuditManager(database)
    }

    @Provides
    @Singleton
    fun provideAuthManager(
        database: NutriOpsDatabase,
        auditManager: AuditManager
    ): AuthManager {
        return AuthManager(database, auditManager)
    }

    @Provides
    @Singleton
    fun provideEncryptionManager(@ApplicationContext context: Context): EncryptionManager {
        return EncryptionManager(context)
    }
}

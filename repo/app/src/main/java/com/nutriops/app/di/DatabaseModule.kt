package com.nutriops.app.di

import android.content.Context
import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.DatabaseFactory
import com.nutriops.app.data.local.NutriOpsDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NutriOpsDatabase {
        return DatabaseFactory.create(context, AppConfig.DB_ENCRYPTION_KEY)
    }
}

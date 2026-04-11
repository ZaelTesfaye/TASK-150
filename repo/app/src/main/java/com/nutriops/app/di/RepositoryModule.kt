package com.nutriops.app.di

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.*
import com.nutriops.app.security.EncryptionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideUserRepository(db: NutriOpsDatabase, audit: AuditManager): UserRepository {
        return UserRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideProfileRepository(db: NutriOpsDatabase, audit: AuditManager): ProfileRepository {
        return ProfileRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideMealPlanRepository(db: NutriOpsDatabase, audit: AuditManager): MealPlanRepository {
        return MealPlanRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideLearningPlanRepository(db: NutriOpsDatabase, audit: AuditManager): LearningPlanRepository {
        return LearningPlanRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideTicketRepository(db: NutriOpsDatabase, audit: AuditManager, encryption: EncryptionManager): TicketRepository {
        return TicketRepository(db, audit, encryption)
    }

    @Provides
    @Singleton
    fun provideConfigRepository(db: NutriOpsDatabase, audit: AuditManager): ConfigRepository {
        return ConfigRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideRuleRepository(db: NutriOpsDatabase, audit: AuditManager): RuleRepository {
        return RuleRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideRolloutRepository(db: NutriOpsDatabase, audit: AuditManager): RolloutRepository {
        return RolloutRepository(db, audit)
    }

    @Provides
    @Singleton
    fun provideMessageRepository(db: NutriOpsDatabase): MessageRepository {
        return MessageRepository(db)
    }

    @Provides
    @Singleton
    fun provideOrderRepository(db: NutriOpsDatabase, audit: AuditManager, encryption: EncryptionManager): OrderRepository {
        return OrderRepository(db, audit, encryption)
    }
}

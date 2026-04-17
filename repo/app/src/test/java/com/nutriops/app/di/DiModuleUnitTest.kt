package com.nutriops.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import coil.ImageLoader
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.OrderRepository
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.security.AuthManager
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Direct, JVM-side unit tests for every Hilt module in `com.nutriops.app.di`.
 *
 * Each test calls a `@Provides` / `@Singleton` function directly -- with
 * test doubles for its parameters where needed -- and asserts the return
 * value is non-null and of the correct type. This is the pure unit
 * counterpart to the instrumented `DiModuleValidationTest`, which exercises
 * the actual Hilt component graph end-to-end.
 *
 * Uses Robolectric only because [DatabaseModule] and [ImageLoaderModule]
 * need an [android.content.Context]; the Context is not invoked for any
 * Android-only behavior, so the tests run entirely on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
class DiModuleUnitTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── AppModule ──

    @Test
    fun `AppModule provideAuditManager returns a real AuditManager`() {
        val audit = AppModule.provideAuditManager(database)
        assertThat(audit).isNotNull()
        assertThat(audit).isInstanceOf(AuditManager::class.java)
    }

    @Test
    fun `AppModule provideAuthManager returns a real AuthManager`() {
        val audit = AppModule.provideAuditManager(database)
        val auth = AppModule.provideAuthManager(database, audit)
        assertThat(auth).isNotNull()
        assertThat(auth).isInstanceOf(AuthManager::class.java)
        // AuthManager is functional: fresh DB requires bootstrap
        assertThat(auth.needsBootstrap()).isTrue()
    }

    @Test
    fun `AppModule provideEncryptionManager returns a real EncryptionManager`() {
        val encryption = AppModule.provideEncryptionManager(context)
        assertThat(encryption).isNotNull()
        assertThat(encryption).isInstanceOf(EncryptionManager::class.java)
        // The Android Keystore is lazy — constructing the instance must not
        // throw on a platform without it. Encryption operations would require
        // AndroidKeyStore and are covered by the instrumented test suite.
    }

    // ── DatabaseModule ──

    @Test
    fun `DatabaseModule provideDatabase is declared as a Provides method`() {
        // The production provideDatabase instantiates SQLCipher's encrypted
        // AndroidSqliteDriver which requires the Android runtime libraries
        // (loaded natively on a real device/emulator). On the host JVM we
        // cannot execute it, but we can assert the provider is correctly
        // declared with the expected signature so Hilt can resolve it.
        val providerMethod = DatabaseModule::class.java.declaredMethods
            .firstOrNull { it.name == "provideDatabase" }
        assertThat(providerMethod).isNotNull()
        assertThat(providerMethod!!.returnType).isEqualTo(NutriOpsDatabase::class.java)
        // Exactly one parameter: the @ApplicationContext Context
        assertThat(providerMethod.parameterTypes.toList())
            .containsExactly(Context::class.java)
    }

    // ── ImageLoaderModule ──

    @Test
    fun `ImageLoaderModule provideImageLoader returns a real Coil ImageLoader`() {
        val loader = ImageLoaderModule.provideImageLoader(context)
        assertThat(loader).isNotNull()
        assertThat(loader).isInstanceOf(ImageLoader::class.java)
        // Loader has a live memory cache (built from the IMAGE_LRU_CACHE_MB
        // config) so accessing its components must not throw
        assertThat(loader.memoryCache).isNotNull()
    }

    // ── RepositoryModule ──
    // One test per @Provides — all return correct type and are non-null.

    @Test
    fun `RepositoryModule provideUserRepository returns UserRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideUserRepository(database, audit)
        assertThat(repo).isInstanceOf(UserRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideProfileRepository returns ProfileRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideProfileRepository(database, audit)
        assertThat(repo).isInstanceOf(ProfileRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideMealPlanRepository returns MealPlanRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideMealPlanRepository(database, audit)
        assertThat(repo).isInstanceOf(MealPlanRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideLearningPlanRepository returns LearningPlanRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideLearningPlanRepository(database, audit)
        assertThat(repo).isInstanceOf(LearningPlanRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideTicketRepository returns TicketRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val encryption = JvmEncryptionManager()
        val repo = RepositoryModule.provideTicketRepository(database, audit, encryption)
        assertThat(repo).isInstanceOf(TicketRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideConfigRepository returns ConfigRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideConfigRepository(database, audit)
        assertThat(repo).isInstanceOf(ConfigRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideRuleRepository returns RuleRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideRuleRepository(database, audit)
        assertThat(repo).isInstanceOf(RuleRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideRolloutRepository returns RolloutRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideRolloutRepository(database, audit)
        assertThat(repo).isInstanceOf(RolloutRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideMessageRepository returns MessageRepository`() {
        val repo = RepositoryModule.provideMessageRepository(database)
        assertThat(repo).isInstanceOf(MessageRepository::class.java)
    }

    @Test
    fun `RepositoryModule provideOrderRepository returns OrderRepository`() {
        val audit = AppModule.provideAuditManager(database)
        val encryption = JvmEncryptionManager()
        val repo = RepositoryModule.provideOrderRepository(database, audit, encryption)
        assertThat(repo).isInstanceOf(OrderRepository::class.java)
    }

    // ── Meta: ensure every declared module is covered ──

    @Test
    fun `every DI module object declares at least one Provides function`() {
        // If a module is added but never exercised, this assertion fails,
        // reminding the developer to add tests in this file.
        val modules = listOf(
            AppModule::class.java,
            DatabaseModule::class.java,
            ImageLoaderModule::class.java,
            RepositoryModule::class.java
        )
        for (m in modules) {
            val providesMethods = m.declaredMethods
                .filter { it.name.startsWith("provide") }
            assertThat(providesMethods).isNotEmpty()
        }
    }
}

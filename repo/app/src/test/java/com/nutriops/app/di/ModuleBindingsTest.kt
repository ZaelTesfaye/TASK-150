package com.nutriops.app.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.security.AuthManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * JVM-side validation of the Hilt provider functions declared in
 * [AppModule] and [RepositoryModule]. Hilt's `@HiltAndroidTest` pipeline is
 * instrumented-only; this test directly invokes each `@Provides` function as
 * a plain Kotlin method, supplying the same dependency graph Hilt would build
 * at runtime.
 *
 * Covers:
 *   • every provider returns a non-null instance
 *   • instances are functionally correct (not just shells)
 *   • transitive dependencies resolve without manual hand-wiring in the
 *     provider (i.e., the call chain matches production)
 *
 * [DatabaseModule] uses `DatabaseFactory` + SQLCipher which require the
 * Android SQLite driver; it is validated in the instrumented
 * [com.nutriops.app.di.DiModuleValidationTest] instead.
 */
class ModuleBindingsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase

    @Before
    fun setup() {
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
    fun `AppModule provideAuditManager returns a working AuditManager`() {
        val audit = AppModule.provideAuditManager(database)

        assertThat(audit).isNotNull()
        // Behavioral assertion: write and read one audit row via the returned
        // binding to prove it is wired to the live DB we passed in.
        audit.log(
            entityType = "Unit", entityId = "u1",
            action = com.nutriops.app.domain.model.AuditAction.CREATE,
            actorId = "actor", actorRole = Role.ADMINISTRATOR
        )
        assertThat(audit.getAuditTrail("Unit", "u1")).hasSize(1)
    }

    @Test
    fun `AppModule provideAuthManager returns an AuthManager that can bootstrap an admin`() {
        val audit = AppModule.provideAuditManager(database)
        val auth = AppModule.provideAuthManager(database, audit)

        assertThat(auth).isNotNull()
        assertThat(auth.needsBootstrap()).isTrue()
        val result = auth.bootstrapAdmin("admin", "AdminPass1!")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        assertThat(auth.needsBootstrap()).isFalse()
    }

    // ── RepositoryModule ── direct @Provides exercise

    @Test
    fun `RepositoryModule provideUserRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideUserRepository(database, audit)

        assertThat(repo).isInstanceOf(UserRepository::class.java)
        val created = repo.createUser("alice", "password123", Role.END_USER, "actor", Role.ADMINISTRATOR)
        assertThat(created.isSuccess).isTrue()
        assertThat(repo.getUserByUsername("alice")).isNotNull()
    }

    @Test
    fun `RepositoryModule provideProfileRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideProfileRepository(database, audit)

        assertThat(repo).isInstanceOf(ProfileRepository::class.java)
        val created = repo.createProfile(
            userId = "user1", ageRange = "26-35", dietaryPattern = "STANDARD",
            allergies = "[]", goal = "MAINTAIN", preferredMealTimes = "[]",
            dailyCalorieBudget = 2000L, proteinTargetGrams = 120L,
            carbTargetGrams = 220L, fatTargetGrams = 67L,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(created.isSuccess).isTrue()
        assertThat(repo.getProfileByUserId("user1")).isNotNull()
    }

    @Test
    fun `RepositoryModule provideMealPlanRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideMealPlanRepository(database, audit)

        assertThat(repo).isInstanceOf(MealPlanRepository::class.java)
        val planId = repo.createMealPlan(
            userId = "user1",
            weekStartDate = "2026-04-06", weekEndDate = "2026-04-12",
            dailyCalorieBudget = 2000L, proteinTarget = 120L,
            carbTarget = 220L, fatTarget = 67L,
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()
        assertThat(planId).isNotNull()
        assertThat(repo.getMealPlanById(planId!!)).isNotNull()
    }

    @Test
    fun `RepositoryModule provideLearningPlanRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideLearningPlanRepository(database, audit)

        assertThat(repo).isInstanceOf(LearningPlanRepository::class.java)
        val planId = repo.createLearningPlan(
            userId = "user1", title = "t", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01",
            frequencyPerWeek = 3, actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()
        assertThat(planId).isNotNull()
        assertThat(repo.getLearningPlansByUserId("user1")).hasSize(1)
    }

    @Test
    fun `RepositoryModule provideTicketRepository produces a working repo with encryption`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val encryption = JvmEncryptionManager()
        val repo = RepositoryModule.provideTicketRepository(database, audit, encryption)

        assertThat(repo).isInstanceOf(TicketRepository::class.java)
        val ticketId = repo.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "s", description = "d",
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()
        assertThat(ticketId).isNotNull()
        assertThat(repo.getTicketById(ticketId!!)).isNotNull()
    }

    @Test
    fun `RepositoryModule provideConfigRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideConfigRepository(database, audit)

        assertThat(repo).isInstanceOf(ConfigRepository::class.java)
        val id = repo.createConfig("k", "v", "admin1", Role.ADMINISTRATOR).getOrNull()
        assertThat(id).isNotNull()
        assertThat(repo.getConfigByKey("k")).isNotNull()
    }

    @Test
    fun `RepositoryModule provideRuleRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideRuleRepository(database, audit)

        assertThat(repo).isInstanceOf(RuleRepository::class.java)
        val id = repo.createRule(
            name = "r", description = "", ruleType = "ADHERENCE",
            conditionsJson = "{}", hysteresisEnter = 80.0, hysteresisExit = 90.0,
            minimumDurationMinutes = 0, effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()
        assertThat(id).isNotNull()
        assertThat(repo.getRuleById(id!!)).isNotNull()
    }

    @Test
    fun `RepositoryModule provideRolloutRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val repo = RepositoryModule.provideRolloutRepository(database, audit)

        assertThat(repo).isInstanceOf(RolloutRepository::class.java)
        val id = repo.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()
        assertThat(id).isNotNull()
        assertThat(repo.getRolloutById(id!!)).isNotNull()
    }

    @Test
    fun `RepositoryModule provideMessageRepository produces a working repo`(): Unit = runBlocking {
        val repo = RepositoryModule.provideMessageRepository(database)

        assertThat(repo).isInstanceOf(MessageRepository::class.java)
        val id = repo.sendMessage(
            userId = "user1", templateId = null, title = "hi", body = "b",
            messageType = "NOTIFICATION", triggerEvent = "TEST"
        ).getOrNull()
        assertThat(id).isNotNull()
        assertThat(repo.getMessagesByUserId("user1")).hasSize(1)
    }

    @Test
    fun `RepositoryModule provideOrderRepository produces a working repo`(): Unit = runBlocking {
        val audit = AppModule.provideAuditManager(database)
        val encryption = JvmEncryptionManager()
        val repo = RepositoryModule.provideOrderRepository(database, audit, encryption)

        assertThat(repo).isInstanceOf(OrderRepository::class.java)
        val id = repo.createOrder("user1", 25.0, "admin1", Role.ADMINISTRATOR).getOrNull()
        assertThat(id).isNotNull()
        assertThat(repo.getOrderById(id!!)).isNotNull()
    }

    // ── Cross-module wiring ──

    @Test
    fun `downstream providers accept the same AuditManager instance without re-wiring`(): Unit = runBlocking {
        // This asserts the Hilt wiring contract: AppModule.provideAuditManager
        // produces the single instance that every RepositoryModule provider
        // receives. We simulate that by calling AppModule once, then feeding
        // the result to every Repository provider.
        val audit: AuditManager = AppModule.provideAuditManager(database)
        val userRepo = RepositoryModule.provideUserRepository(database, audit)
        val configRepo = RepositoryModule.provideConfigRepository(database, audit)

        userRepo.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        configRepo.createConfig("feature.x", "on", "admin1", Role.ADMINISTRATOR)

        // Both writes must have gone through the same audit table
        val allAudit = audit.getRecentEvents(10L)
        assertThat(allAudit).isNotEmpty()
        val entityTypes = allAudit.map { it.entityType }.toSet()
        assertThat(entityTypes).containsAtLeast("User", "Config")
    }
}

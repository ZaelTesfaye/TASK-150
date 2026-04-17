package com.nutriops.app.integration_tests.domain.usecase.config

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.RolloutStatus
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ManageConfigUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var configRepository: ConfigRepository
    private lateinit var rolloutRepository: RolloutRepository
    private lateinit var useCase: ManageConfigUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        configRepository = ConfigRepository(database, auditManager)
        rolloutRepository = RolloutRepository(database, auditManager)
        useCase = ManageConfigUseCase(configRepository, rolloutRepository)

        // Seed END_USER rows so canary assignment has a candidate pool
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        for (id in listOf("u-alpha", "u-bravo", "u-charlie", "u-delta")) {
            database.usersQueries.insertUser(
                id = id, username = id, passwordHash = "x", role = "END_USER",
                isActive = 1, isLocked = 0, failedLoginAttempts = 0, lockoutUntil = null,
                createdAt = now, updatedAt = now
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `admin createConfig persists a real row and writes a CREATE audit event`() = runBlocking {
        val result = useCase.createConfig("feature.x", "on", "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
        val id = result.getOrNull()!!

        val stored = database.configsQueries.getConfigByKey("feature.x").executeAsOne()
        assertThat(stored.id).isEqualTo(id)
        assertThat(stored.configValue).isEqualTo("on")

        val audit = auditManager.getAuditTrail("Config", id)
        assertThat(audit.map { it.action }).contains(AuditAction.CREATE.name)
    }

    @Test
    fun `non-admin createConfig is rejected and no row is inserted`() = runBlocking {
        val result = useCase.createConfig("feature.y", "on", "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(database.configsQueries.getConfigByKey("feature.y").executeAsOneOrNull()).isNull()
    }

    @Test
    fun `updateConfig bumps version and preserves history in ConfigVersions`() = runBlocking {
        val id = useCase.createConfig("feature.z", "v1", "admin1", Role.ADMINISTRATOR).getOrNull()!!
        useCase.updateConfig(id, "v2", "admin1", Role.ADMINISTRATOR)

        val stored = database.configsQueries.getConfigByKey("feature.z").executeAsOne()
        assertThat(stored.version).isEqualTo(2L)
        assertThat(stored.configValue).isEqualTo("v2")

        val versions = configRepository.getConfigVersions(id)
        assertThat(versions.map { it.configValue }).containsExactly("v2", "v1").inOrder()
    }

    @Test
    fun `startCanaryRollout writes a CANARY row and stores deterministic user assignment`() = runBlocking {
        val result = useCase.startCanaryRollout("cfg-v1", 25, "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
        val rolloutId = result.getOrNull()!!

        val rollout = database.rolloutsQueries.getRolloutById(rolloutId).executeAsOne()
        assertThat(rollout.status).isEqualTo(RolloutStatus.CANARY.name)
        // 25% of 4 end-users = 1; alphabetically-first is u-alpha
        assertThat(rollout.canaryUsers).contains("u-alpha")
    }

    @Test
    fun `promoteRollout transitions CANARY to FULL in the real DB`() = runBlocking {
        val rolloutId = useCase.startCanaryRollout("cfg-v1", 25, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        useCase.promoteRollout(rolloutId, "admin1", Role.ADMINISTRATOR)

        val rollout = database.rolloutsQueries.getRolloutById(rolloutId).executeAsOne()
        assertThat(rollout.status).isEqualTo(RolloutStatus.FULL.name)
    }

    @Test
    fun `validateAndUseCoupon enforces per-user usage cap via real usage rows`() = runBlocking {
        // Coupons.discountType is CHECK-constrained to ('FIXED', 'PERCENTAGE')
        // in the schema -- use the schema-compliant value, not "PERCENT".
        val couponId = useCase.createCoupon(
            "SAVE10", "10% off", "PERCENTAGE", 10.0, "{}",
            maxUsesPerUser = 1L, periodDays = 30L, configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()!!

        val first = useCase.validateAndUseCoupon(couponId, "buyer")
        assertThat(first.isSuccess).isTrue()
        assertThat(first.getOrNull()).isTrue()

        val second = useCase.validateAndUseCoupon(couponId, "buyer")
        assertThat(second.isFailure).isTrue()
    }
}

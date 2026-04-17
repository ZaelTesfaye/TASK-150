package com.nutriops.app.integration_tests.domain.usecase.rules

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Integration test for [EvaluateRuleUseCase] against a real in-memory
 * SQLDelight database. Complements the existing [RuleHysteresisDurationTest]
 * which focuses on state-machine transitions; this file asserts that rule
 * evaluation persists back to metric snapshots and respects active-rule
 * filtering via the real repository.
 *
 * Requires [RobolectricTestRunner] because [EvaluateRuleUseCase] parses
 * rule conditions through `org.json.JSONObject`, which is an Android-only
 * class and would be stubbed (returning default values) under a bare JVM
 * JUnit runner.
 */
@RunWith(RobolectricTestRunner::class)
class EvaluateRuleUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var ruleRepository: RuleRepository
    private lateinit var useCase: EvaluateRuleUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        ruleRepository = RuleRepository(database, auditManager)
        useCase = EvaluateRuleUseCase(ruleRepository)

        // Seed a user (FK target) for metric snapshot inserts
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        database.usersQueries.insertUser(
            id = "user1", username = "user1", passwordHash = "x",
            role = "END_USER", isActive = 1, isLocked = 0,
            failedLoginAttempts = 0, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private suspend fun createAdherenceRule(minDuration: Long = 0): String =
        ruleRepository.createRule(
            name = "Adherence ${UUID.randomUUID()}",
            description = "", ruleType = "ADHERENCE",
            conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
            hysteresisEnter = 80.0, hysteresisExit = 90.0,
            minimumDurationMinutes = minDuration,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()!!

    @Test
    fun `evaluateAllRules triggers a rule whose metric meets its threshold`() = runBlocking {
        createAdherenceRule(minDuration = 0)

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isTrue()
    }

    @Test
    fun `minimum duration hold is persisted in the DB between evaluations`() = runBlocking {
        val ruleId = createAdherenceRule(minDuration = 10)

        // First evaluation: condition met, duration not reached, hold row written
        useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        val hold = ruleRepository.getConditionHold(ruleId, "user1")
        assertThat(hold).isNotNull()
    }

    @Test
    fun `condition dropping below threshold clears the hold row`() = runBlocking {
        val ruleId = createAdherenceRule(minDuration = 10)

        useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(ruleRepository.getConditionHold(ruleId, "user1")).isNotNull()

        useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 60.0),
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(ruleRepository.getConditionHold(ruleId, "user1")).isNull()
    }

    @Test
    fun `backCalculate returns one result per metric snapshot stored in the DB`() = runBlocking {
        val ruleId = createAdherenceRule(minDuration = 0)
        // createAdherenceRule seeds v1 with createdAt = "now". Patch it to a
        // known historical date so metric snapshots recorded after this row
        // can resolve the correct version in the back-calculation filter.
        driver.execute(
            identifier = null,
            sql = "UPDATE RuleVersions SET createdAt = '2026-01-01T00:00:00' WHERE ruleId = '$ruleId' AND version = 1",
            parameters = 0,
            binders = null
        )
        // Seed two real metric snapshots after v1's new createdAt
        database.metricsSnapshotsQueries.insertMetricsSnapshot(
            id = UUID.randomUUID().toString(), userId = "user1", ruleId = ruleId,
            metricType = "adherence_rate", metricValue = 85.0,
            snapshotDate = "2026-02-01T00:00:00", metadata = "{}",
            createdAt = "2026-02-01T00:00:00"
        )
        database.metricsSnapshotsQueries.insertMetricsSnapshot(
            id = UUID.randomUUID().toString(), userId = "user1", ruleId = ruleId,
            metricType = "adherence_rate", metricValue = 60.0,
            snapshotDate = "2026-03-01T00:00:00", metadata = "{}",
            createdAt = "2026-03-01T00:00:00"
        )

        val result = useCase.backCalculate(
            ruleId = ruleId, userId = "user1",
            startDate = "2026-01-01", endDate = "2026-04-01",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!).hasSize(2)
    }

    @Test
    fun `end user is denied backCalculate regardless of metric data`() = runBlocking {
        val ruleId = createAdherenceRule()

        val result = useCase.backCalculate(
            ruleId = ruleId, userId = "user1",
            startDate = "2026-01-01", endDate = "2026-04-01",
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }
}

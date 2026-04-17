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
 * Repository-backed tests for rule hysteresis, minimum-duration hold, and
 * back-calc behavior. Runs against a real in-memory SQLDelight database so
 * persistence boundaries (condition-hold upsert/delete, metric snapshot
 * lookups, rule version selection) are exercised end-to-end.
 *
 * Uses [RobolectricTestRunner] because [EvaluateRuleUseCase.parseCondition]
 * relies on `org.json.JSONObject`, which is an Android-only class -- on a
 * plain JVM JUnit runner with `isReturnDefaultValues = true` it returns stub
 * values that silently degrade condition parsing.
 */
@RunWith(RobolectricTestRunner::class)
class RuleHysteresisDurationTest {

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

        // Seed users — Rule.createdBy / MetricsSnapshots.userId reference Users(id).
        insertUser("user1")
        insertUser("admin1")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Condition-tree evaluation (pure functions, no DB) ──

    @Test
    fun `hysteresis - at enter threshold triggers`() {
        val condition = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 80.0))).isTrue()
    }

    @Test
    fun `hysteresis - below enter threshold does not trigger`() {
        val condition = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 75.0))).isFalse()
    }

    @Test
    fun `not-equal operator`() {
        val cond = EvaluateRuleUseCase.ConditionNode("metric", "score", "!=", 100.0, null)
        assertThat(useCase.evaluateCondition(cond, mapOf("score" to 99.0))).isTrue()
        assertThat(useCase.evaluateCondition(cond, mapOf("score" to 100.0))).isFalse()
    }

    @Test
    fun `compound AND - partial failure prevents trigger`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "completion_rate", ">=", 70.0, null)
        val andNode = EvaluateRuleUseCase.ConditionNode("and", null, null, null, listOf(condA, condB))
        assertThat(
            useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 90.0, "completion_rate" to 60.0))
        ).isFalse()
    }

    @Test
    fun `compound OR - one passing is enough`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 90.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "exception_count", "<", 2.0, null)
        val orNode = EvaluateRuleUseCase.ConditionNode("or", null, null, null, listOf(condA, condB))
        assertThat(
            useCase.evaluateCondition(orNode, mapOf("adherence_rate" to 70.0, "exception_count" to 1.0))
        ).isTrue()
    }

    // ── Minimum-duration hold via evaluateAllRules ──

    @Test
    fun `first evaluation records hold start and does NOT trigger`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 10)

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isFalse()
        assertThat(eval.reason).contains("not yet reached")

        // A hold row must have been written
        val hold = database.rulesQueries.getConditionHold(ruleId, "user1").executeAsOneOrNull()
        assertThat(hold).isNotNull()
    }

    @Test
    fun `hold duration not yet met keeps rule untriggered`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 10)
        insertConditionHold(ruleId, "user1", LocalDateTime.now().minusMinutes(5))

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isFalse()
        assertThat(eval.reason).contains("not yet reached")
    }

    @Test
    fun `hold duration met triggers the rule`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 10)
        insertConditionHold(ruleId, "user1", LocalDateTime.now().minusMinutes(15))

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isTrue()
        assertThat(eval.reason).contains("minimum duration")
    }

    @Test
    fun `condition no longer met clears the hold`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 10)
        insertConditionHold(ruleId, "user1", LocalDateTime.now().minusMinutes(15))

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 70.0), // below threshold
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isFalse()
        // The hold row must have been deleted
        val hold = database.rulesQueries.getConditionHold(ruleId, "user1").executeAsOneOrNull()
        assertThat(hold).isNull()
    }

    @Test
    fun `rule with zero minimumDuration triggers immediately`() = runBlocking {
        insertRule(minimumDurationMinutes = 0)

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isTrue()
    }

    // ── Hysteresis via evaluateAllRules ──

    @Test
    fun `already triggered rule stays triggered above exit threshold`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 0, hysteresisEnter = 80.0, hysteresisExit = 90.0)
        // Record a prior metric above enter threshold → previously triggered
        insertMetricSnapshot(ruleId, "user1", metricType = "adherence_rate", value = 85.0)

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 92.0), // above exit 90 → stays triggered
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isTrue()
    }

    @Test
    fun `already triggered rule un-triggers below exit threshold`() = runBlocking {
        val ruleId = insertRule(minimumDurationMinutes = 0, hysteresisEnter = 80.0, hysteresisExit = 90.0)
        insertMetricSnapshot(ruleId, "user1", metricType = "adherence_rate", value = 85.0)

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0), // below exit 90 → un-triggers
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isFalse()
    }

    // ── Back-calculation with rule version replay ──

    @Test
    fun `back-calculate selects correct rule version per metric date`() = runBlocking {
        val ruleId = insertRule(
            minimumDurationMinutes = 0,
            hysteresisEnter = 90.0,
            conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":90.0}"""
        )
        // insertRule() seeds v1 with createdAt = "now". Patch that row to a
        // known historical timestamp so both metric snapshots land after it.
        driver.execute(
            identifier = null,
            sql = "UPDATE RuleVersions SET createdAt = '2026-01-01T00:00:00' WHERE ruleId = '$ruleId' AND version = 1",
            parameters = 0,
            binders = null
        )
        // v2: threshold lowered to 70, effective from Feb 1
        insertRuleVersion(
            ruleId = ruleId,
            version = 2L,
            conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":70.0}""",
            hysteresisEnter = 70.0,
            createdAt = "2026-02-01T00:00:00"
        )

        // Metric 1 recorded under v1 (Jan 15) with value 80 -> v1 threshold 90 -> not triggered
        insertMetricSnapshot(ruleId, "user1", "adherence_rate", 80.0, snapshotDate = "2026-01-15T12:00:00")
        // Metric 2 recorded under v2 (Feb 15) with value 80 -> v2 threshold 70 -> triggered
        insertMetricSnapshot(ruleId, "user1", "adherence_rate", 80.0, snapshotDate = "2026-02-15T12:00:00")

        val result = useCase.backCalculate(
            ruleId = ruleId, userId = "user1",
            startDate = "2026-01-01", endDate = "2026-03-01",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val results = result.getOrNull()!!
        assertThat(results).hasSize(2)
        // Sorted by snapshot date ascending
        assertThat(results[0].triggered).isFalse()
        assertThat(results[0].version).isEqualTo(1L)
        assertThat(results[1].triggered).isTrue()
        assertThat(results[1].version).isEqualTo(2L)
    }

    @Test
    fun `back-calculate denied for end user`() = runBlocking {
        val result = useCase.backCalculate(
            ruleId = "rule1", userId = "user1",
            startDate = "2026-01-01", endDate = "2026-03-01",
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `back-calculate with no metrics returns empty`() = runBlocking {
        val ruleId = insertRule()

        val result = useCase.backCalculate(
            ruleId = ruleId, userId = "user1",
            startDate = "2026-01-01", endDate = "2026-03-01",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    // ── Effective window ──

    @Test
    fun `rule outside effective window is skipped`() = runBlocking {
        insertRule(
            minimumDurationMinutes = 0,
            effectiveWindowStart = "2025-01-01T00:00:00",
            effectiveWindowEnd = "2025-06-01T00:00:00"
        )

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 99.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        // Window ended 2025-06 — today (2026) is outside
        assertThat(result.getOrNull()!!).isEmpty()
    }

    // ── Helpers ──

    private fun insertUser(id: String) {
        val now = nowIso()
        database.usersQueries.insertUser(
            id = id, username = id, passwordHash = "x", role = "ADMINISTRATOR",
            isActive = 1, isLocked = 0, failedLoginAttempts = 0, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )
    }

    private fun insertRule(
        minimumDurationMinutes: Long = 10,
        hysteresisEnter: Double = 80.0,
        hysteresisExit: Double = 90.0,
        conditionsJson: String =
            """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
        effectiveWindowStart: String? = null,
        effectiveWindowEnd: String? = null
    ): String {
        val ruleId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        val now = nowIso()
        database.rulesQueries.insertRule(
            ruleId, "Test Rule", "", "ADHERENCE", conditionsJson,
            hysteresisEnter, hysteresisExit, minimumDurationMinutes,
            effectiveWindowStart, effectiveWindowEnd,
            1, 1, "admin1", now, now
        )
        database.rulesQueries.insertRuleVersion(
            versionId, ruleId, 1, conditionsJson,
            hysteresisEnter, hysteresisExit, minimumDurationMinutes,
            effectiveWindowStart, effectiveWindowEnd, "admin1", now
        )
        return ruleId
    }

    private fun insertRuleVersion(
        ruleId: String,
        version: Long,
        conditionsJson: String,
        hysteresisEnter: Double = 80.0,
        hysteresisExit: Double = 90.0,
        minimumDurationMinutes: Long = 0,
        createdAt: String = nowIso()
    ) {
        database.rulesQueries.insertRuleVersion(
            UUID.randomUUID().toString(), ruleId, version, conditionsJson,
            hysteresisEnter, hysteresisExit, minimumDurationMinutes,
            null, null, "admin1", createdAt
        )
    }

    private fun insertConditionHold(ruleId: String, userId: String, startedAt: LocalDateTime) {
        database.rulesQueries.upsertConditionHold(
            ruleId, userId, startedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    private fun insertMetricSnapshot(
        ruleId: String, userId: String, metricType: String, value: Double,
        snapshotDate: String = nowIso()
    ) {
        database.metricsSnapshotsQueries.insertMetricsSnapshot(
            id = UUID.randomUUID().toString(),
            userId = userId, ruleId = ruleId,
            metricType = metricType, metricValue = value,
            snapshotDate = snapshotDate, metadata = "{}",
            createdAt = nowIso()
        )
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RuleRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: RuleRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = RuleRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createRule ──

    @Test
    fun `createRule persists rule and version 1, plus audit entry`(): Unit = runBlocking {
        val result = repository.createRule(
            name = "High adherence",
            description = "Trigger when adherence drops",
            ruleType = "ADHERENCE",
            conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
            hysteresisEnter = 80.0, hysteresisExit = 90.0,
            minimumDurationMinutes = 10,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()
        val ruleId = result.getOrNull()!!

        val stored = repository.getRuleById(ruleId)!!
        assertThat(stored.name).isEqualTo("High adherence")
        assertThat(stored.ruleType).isEqualTo("ADHERENCE")
        assertThat(stored.hysteresisEnterPercent).isEqualTo(80.0)
        assertThat(stored.hysteresisExitPercent).isEqualTo(90.0)
        assertThat(stored.minimumDurationMinutes).isEqualTo(10L)
        assertThat(stored.currentVersion).isEqualTo(1L)
        assertThat(stored.isActive).isEqualTo(1L)

        val versions = repository.getRuleVersions(ruleId)
        assertThat(versions).hasSize(1)
        assertThat(versions.first().version).isEqualTo(1L)

        val audit = auditManager.getAuditTrail("Rule", ruleId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    @Test
    fun `createRule with invalid ruleType CHECK constraint fails`(): Unit = runBlocking {
        val result = repository.createRule(
            name = "bad-type", description = "", ruleType = "INVALID_TYPE",
            conditionsJson = "{}", hysteresisEnter = 80.0, hysteresisExit = 90.0,
            minimumDurationMinutes = 0, effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isFailure).isTrue()
    }

    // ── updateRule ──

    @Test
    fun `updateRule persists new condition and bumps version`(): Unit = runBlocking {
        val ruleId = createRule("r1")

        val result = repository.updateRule(
            ruleId = ruleId,
            name = "r1-updated", description = "newer",
            ruleType = "ADHERENCE",
            conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":70.0}""",
            hysteresisEnter = 70.0, hysteresisExit = 85.0,
            minimumDurationMinutes = 5,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()

        val stored = repository.getRuleById(ruleId)!!
        assertThat(stored.name).isEqualTo("r1-updated")
        assertThat(stored.currentVersion).isEqualTo(2L)
        assertThat(stored.hysteresisEnterPercent).isEqualTo(70.0)
        assertThat(stored.minimumDurationMinutes).isEqualTo(5L)

        // Two versions in the history
        val versions = repository.getRuleVersions(ruleId)
        assertThat(versions).hasSize(2)
        assertThat(versions.map { it.version }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `updateRule fails for unknown rule id`(): Unit = runBlocking {
        val result = repository.updateRule(
            ruleId = "missing", name = "", description = "",
            ruleType = "ADHERENCE", conditionsJson = "{}",
            hysteresisEnter = 80.0, hysteresisExit = 90.0,
            minimumDurationMinutes = 10,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `inserting a second RuleVersion row with the same (ruleId, version) fails the UNIQUE constraint`(): Unit = runBlocking {
        val ruleId = createRule("r1")

        val duplicate = runCatching {
            database.rulesQueries.insertRuleVersion(
                id = java.util.UUID.randomUUID().toString(),
                ruleId = ruleId, version = 1L,
                conditionsJson = "{}",
                hysteresisEnterPercent = 80.0, hysteresisExitPercent = 90.0,
                minimumDurationMinutes = 0,
                effectiveWindowStart = null, effectiveWindowEnd = null,
                createdBy = "admin1", createdAt = nowIso()
            )
        }
        assertThat(duplicate.isFailure).isTrue()
    }

    // ── Queries ──

    @Test
    fun `getAllActiveRules returns only active rules`(): Unit = runBlocking {
        val r1 = createRule("r1")
        val r2 = createRule("r2")

        // Manually deactivate r2
        database.rulesQueries.updateRule(
            name = "r2", description = "", ruleType = "ADHERENCE",
            conditionsJson = "{}", hysteresisEnterPercent = 80.0,
            hysteresisExitPercent = 90.0, minimumDurationMinutes = 10,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            currentVersion = 1, isActive = 0, updatedAt = nowIso(), id = r2
        )

        val active = repository.getAllActiveRules().map { it.id }
        assertThat(active).containsExactly(r1)
    }

    @Test
    fun `getRulesByType filters by context key (ruleType)`(): Unit = runBlocking {
        createRule("a1", type = "ADHERENCE")
        createRule("e1", type = "EXCEPTION")
        createRule("k1", type = "OPERATIONAL_KPI")

        val adherence = repository.getRulesByType("ADHERENCE")
        val exceptions = repository.getRulesByType("EXCEPTION")
        val kpis = repository.getRulesByType("OPERATIONAL_KPI")

        assertThat(adherence.map { it.name }).containsExactly("a1")
        assertThat(exceptions.map { it.name }).containsExactly("e1")
        assertThat(kpis.map { it.name }).containsExactly("k1")
    }

    @Test
    fun `getRuleVersion finds specific version by id and version number`(): Unit = runBlocking {
        val ruleId = createRule("r1")
        repository.updateRule(
            ruleId = ruleId, name = "r1", description = "",
            ruleType = "ADHERENCE",
            conditionsJson = """{"threshold":70}""",
            hysteresisEnter = 70.0, hysteresisExit = 85.0, minimumDurationMinutes = 5,
            effectiveWindowStart = null, effectiveWindowEnd = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        val v1 = repository.getRuleVersion(ruleId, 1L)
        val v2 = repository.getRuleVersion(ruleId, 2L)
        val v3 = repository.getRuleVersion(ruleId, 3L)

        assertThat(v1).isNotNull()
        assertThat(v1!!.conditionsJson).contains("80")
        assertThat(v2).isNotNull()
        assertThat(v2!!.conditionsJson).contains("70")
        assertThat(v3).isNull()
    }

    // ── Condition holds ──

    @Test
    fun `upsert and getConditionHold round-trip`(): Unit = runBlocking {
        val ruleId = createRule("r1")
        repository.upsertConditionHold(ruleId, "user1", nowIso())

        val hold = repository.getConditionHold(ruleId, "user1")
        assertThat(hold).isNotNull()
        assertThat(hold!!.userId).isEqualTo("user1")
    }

    @Test
    fun `deleteConditionHold removes the row`(): Unit = runBlocking {
        val ruleId = createRule("r1")
        repository.upsertConditionHold(ruleId, "user1", nowIso())
        assertThat(repository.getConditionHold(ruleId, "user1")).isNotNull()

        repository.deleteConditionHold(ruleId, "user1")
        assertThat(repository.getConditionHold(ruleId, "user1")).isNull()
    }

    // ── helpers ──

    private suspend fun createRule(
        name: String = "rule",
        type: String = "ADHERENCE"
    ): String = repository.createRule(
        name = name, description = "", ruleType = type,
        conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
        hysteresisEnter = 80.0, hysteresisExit = 90.0,
        minimumDurationMinutes = 10,
        effectiveWindowStart = null, effectiveWindowEnd = null,
        actorId = "admin1", actorRole = Role.ADMINISTRATOR
    ).getOrNull()!!

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

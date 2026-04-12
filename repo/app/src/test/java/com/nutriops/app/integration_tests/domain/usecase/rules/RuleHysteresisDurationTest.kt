package com.nutriops.app.integration_tests.domain.usecase.rules

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.MetricsSnapshots
import com.nutriops.app.data.local.RuleConditionHolds
import com.nutriops.app.data.local.RuleVersions
import com.nutriops.app.data.local.Rules
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Repository-backed tests verifying minimum-duration hold,
 * hysteresis behavior, and back-calc rule-version replay.
 */
@RunWith(RobolectricTestRunner::class)
class RuleHysteresisDurationTest {

    private val ruleRepository: RuleRepository = mockk(relaxed = true)
    private lateinit var useCase: EvaluateRuleUseCase

    private fun makeRule(
        id: String = "rule1",
        name: String = "Test Rule",
        conditionsJson: String = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
        hysteresisEnter: Double = 80.0,
        hysteresisExit: Double = 90.0,
        minimumDurationMinutes: Long = 10,
        effectiveWindowStart: String? = null,
        effectiveWindowEnd: String? = null,
        currentVersion: Long = 1
    ): Rules = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { this@mockk.conditionsJson } returns conditionsJson
        every { this@mockk.hysteresisEnterPercent } returns hysteresisEnter
        every { this@mockk.hysteresisExitPercent } returns hysteresisExit
        every { this@mockk.minimumDurationMinutes } returns minimumDurationMinutes
        every { this@mockk.effectiveWindowStart } returns effectiveWindowStart
        every { this@mockk.effectiveWindowEnd } returns effectiveWindowEnd
        every { this@mockk.currentVersion } returns currentVersion
    }

    @Before
    fun setup() {
        useCase = EvaluateRuleUseCase(ruleRepository)
    }

    // ── Hysteresis via evaluateCondition ──

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

    // ── Minimum-duration hold via evaluateAllRules ──

    @Test
    fun `first evaluation records hold start and does NOT trigger`() = runBlocking {
        val rule = makeRule(minimumDurationMinutes = 10)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns null
        coEvery { ruleRepository.getConditionHold("rule1", "user1") } returns null

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isFalse()
        assertThat(eval.reason).contains("not yet reached")

        // Must have recorded a new hold
        coVerify { ruleRepository.upsertConditionHold("rule1", "user1", any()) }
    }

    @Test
    fun `hold duration not yet met keeps rule untriggered`() = runBlocking {
        val rule = makeRule(minimumDurationMinutes = 10)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns null

        // Hold started 5 minutes ago — not enough
        val fiveMinAgo = LocalDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hold = mockk<RuleConditionHolds> { every { holdStartedAt } returns fiveMinAgo }
        coEvery { ruleRepository.getConditionHold("rule1", "user1") } returns hold

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
        val rule = makeRule(minimumDurationMinutes = 10)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns null

        // Hold started 15 minutes ago — sufficient
        val fifteenMinAgo = LocalDateTime.now().minusMinutes(15).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hold = mockk<RuleConditionHolds> { every { holdStartedAt } returns fifteenMinAgo }
        coEvery { ruleRepository.getConditionHold("rule1", "user1") } returns hold

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
        val rule = makeRule(minimumDurationMinutes = 10)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns null

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 70.0), // below threshold
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        val eval = result.getOrNull()!!.single()
        assertThat(eval.triggered).isFalse()
        coVerify { ruleRepository.deleteConditionHold("rule1", "user1") }
    }

    @Test
    fun `rule with zero minimumDuration triggers immediately`() = runBlocking {
        val rule = makeRule(minimumDurationMinutes = 0)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns null

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isTrue()
    }

    // ── Hysteresis via evaluateAllRules (already-triggered stay-triggered) ──

    @Test
    fun `already triggered rule stays triggered above exit threshold`() = runBlocking {
        val rule = makeRule(minimumDurationMinutes = 0, hysteresisEnter = 80.0, hysteresisExit = 90.0)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)

        // Last metric was above enter → was triggered
        val lastMetric = mockk<MetricsSnapshots> { every { metricValue } returns 85.0 }
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns lastMetric

        // Current metric 92 → still above exit threshold 90 → stays triggered
        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 92.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isTrue()
    }

    @Test
    fun `already triggered rule un-triggers below exit threshold`() = runBlocking {
        val rule = makeRule(minimumDurationMinutes = 0, hysteresisEnter = 80.0, hysteresisExit = 90.0)
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)

        val lastMetric = mockk<MetricsSnapshots> { every { metricValue } returns 85.0 }
        coEvery { ruleRepository.getLatestMetric("user1", "rule1") } returns lastMetric

        // Current metric 85 → below exit threshold 90 → un-triggers
        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 85.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.getOrNull()!!.single().triggered).isFalse()
    }

    // ── Back-calculation with rule version replay ──

    @Test
    fun `back-calculate selects correct rule version per metric date`() = runBlocking {
        val v1Conditions = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":90.0}"""
        val v2Conditions = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":70.0}"""

        val version1 = mockk<RuleVersions> {
            every { version } returns 1L
            every { conditionsJson } returns v1Conditions
            every { hysteresisEnterPercent } returns 90.0
            every { createdAt } returns "2026-01-01T00:00:00"
        }
        val version2 = mockk<RuleVersions> {
            every { version } returns 2L
            every { conditionsJson } returns v2Conditions
            every { hysteresisEnterPercent } returns 70.0
            every { createdAt } returns "2026-02-01T00:00:00"
        }
        coEvery { ruleRepository.getRuleVersions("rule1") } returns listOf(version2, version1)

        // Metric at Jan 15 (v1 active) with value 80 → v1 threshold 90 → not triggered
        val metric1 = mockk<MetricsSnapshots> {
            every { metricType } returns "adherence_rate"
            every { metricValue } returns 80.0
            every { snapshotDate } returns "2026-01-15T12:00:00"
        }
        // Metric at Feb 15 (v2 active) with value 80 → v2 threshold 70 → triggered
        val metric2 = mockk<MetricsSnapshots> {
            every { metricType } returns "adherence_rate"
            every { metricValue } returns 80.0
            every { snapshotDate } returns "2026-02-15T12:00:00"
        }
        coEvery { ruleRepository.getMetricsByRuleAndDateRange("rule1", any(), any()) } returns listOf(metric1, metric2)

        val result = useCase.backCalculate(
            ruleId = "rule1", userId = "user1",
            startDate = "2026-01-01", endDate = "2026-03-01",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val results = result.getOrNull()!!
        assertThat(results).hasSize(2)
        // v1 (threshold 90): 80 < 90 → not triggered
        assertThat(results[0].triggered).isFalse()
        assertThat(results[0].version).isEqualTo(1L)
        // v2 (threshold 70): 80 >= 70 → triggered
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
        coEvery { ruleRepository.getRuleVersions("rule1") } returns emptyList()
        coEvery { ruleRepository.getMetricsByRuleAndDateRange("rule1", any(), any()) } returns emptyList()

        val result = useCase.backCalculate(
            ruleId = "rule1", userId = "user1",
            startDate = "2026-01-01", endDate = "2026-03-01",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    // ── Effective window ──

    @Test
    fun `rule outside effective window is skipped`() = runBlocking {
        val rule = makeRule(
            minimumDurationMinutes = 0,
            effectiveWindowStart = "2025-01-01T00:00:00",
            effectiveWindowEnd = "2025-06-01T00:00:00"
        )
        coEvery { ruleRepository.getAllActiveRules() } returns listOf(rule)
        coEvery { ruleRepository.getLatestMetric(any(), any()) } returns null

        val result = useCase.evaluateAllRules(
            userId = "user1",
            metricsMap = mapOf("adherence_rate" to 99.0),
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        // Rule should be skipped (now is 2026, window ended 2025-06)
        assertThat(result.getOrNull()!!).isEmpty()
    }

    // ── Compound conditions ──

    @Test
    fun `compound AND - partial failure prevents trigger`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "completion_rate", ">=", 70.0, null)
        val andNode = EvaluateRuleUseCase.ConditionNode("and", null, null, null, listOf(condA, condB))
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 90.0, "completion_rate" to 60.0))).isFalse()
    }

    @Test
    fun `compound OR - one passing is enough`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 90.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "exception_count", "<", 2.0, null)
        val orNode = EvaluateRuleUseCase.ConditionNode("or", null, null, null, listOf(condA, condB))
        assertThat(useCase.evaluateCondition(orNode, mapOf("adherence_rate" to 70.0, "exception_count" to 1.0))).isTrue()
    }
}

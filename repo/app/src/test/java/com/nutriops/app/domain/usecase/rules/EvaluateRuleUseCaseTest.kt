package com.nutriops.app.domain.usecase.rules

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EvaluateRuleUseCaseTest {

    private lateinit var useCase: EvaluateRuleUseCase

    @Before
    fun setup() {
        useCase = EvaluateRuleUseCase(ruleRepository = mockk(relaxed = true))
    }

    // ── Simple metric conditions ──

    @Test
    fun `simple greater-than condition evaluates correctly`() {
        val condition = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 85.0))).isTrue()
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 75.0))).isFalse()
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 80.0))).isTrue()
    }

    @Test
    fun `less-than condition evaluates correctly`() {
        val condition = EvaluateRuleUseCase.ConditionNode("metric", "exception_count", "<", 5.0, null)
        assertThat(useCase.evaluateCondition(condition, mapOf("exception_count" to 3.0))).isTrue()
        assertThat(useCase.evaluateCondition(condition, mapOf("exception_count" to 5.0))).isFalse()
        assertThat(useCase.evaluateCondition(condition, mapOf("exception_count" to 7.0))).isFalse()
    }

    @Test
    fun `missing metric returns false`() {
        val condition = EvaluateRuleUseCase.ConditionNode("metric", "unknown_metric", ">=", 50.0, null)
        assertThat(useCase.evaluateCondition(condition, mapOf("adherence_rate" to 85.0))).isFalse()
    }

    // ── Compound conditions: A AND (B OR C) ──

    @Test
    fun `AND compound condition - all must be true`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "completion_rate", ">=", 70.0, null)
        val andNode = EvaluateRuleUseCase.ConditionNode("and", null, null, null, listOf(condA, condB))

        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 85.0, "completion_rate" to 75.0))).isTrue()
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 85.0, "completion_rate" to 60.0))).isFalse()
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 70.0, "completion_rate" to 75.0))).isFalse()
    }

    @Test
    fun `OR compound condition - any can be true`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 90.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "exception_count", "<", 2.0, null)
        val orNode = EvaluateRuleUseCase.ConditionNode("or", null, null, null, listOf(condA, condB))

        assertThat(useCase.evaluateCondition(orNode, mapOf("adherence_rate" to 95.0, "exception_count" to 5.0))).isTrue()
        assertThat(useCase.evaluateCondition(orNode, mapOf("adherence_rate" to 70.0, "exception_count" to 1.0))).isTrue()
        assertThat(useCase.evaluateCondition(orNode, mapOf("adherence_rate" to 70.0, "exception_count" to 3.0))).isFalse()
    }

    @Test
    fun `nested compound condition A AND (B OR C)`() {
        val condA = EvaluateRuleUseCase.ConditionNode("metric", "adherence_rate", ">=", 80.0, null)
        val condB = EvaluateRuleUseCase.ConditionNode("metric", "completion_rate", ">=", 90.0, null)
        val condC = EvaluateRuleUseCase.ConditionNode("metric", "exception_count", "<", 3.0, null)
        val orNode = EvaluateRuleUseCase.ConditionNode("or", null, null, null, listOf(condB, condC))
        val andNode = EvaluateRuleUseCase.ConditionNode("and", null, null, null, listOf(condA, orNode))

        // A=true, B=false, C=true -> true (A AND (B OR C))
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 85.0, "completion_rate" to 50.0, "exception_count" to 1.0))).isTrue()
        // A=true, B=true, C=false -> true
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 85.0, "completion_rate" to 95.0, "exception_count" to 5.0))).isTrue()
        // A=false, B=true, C=true -> false
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 70.0, "completion_rate" to 95.0, "exception_count" to 1.0))).isFalse()
        // A=true, B=false, C=false -> false
        assertThat(useCase.evaluateCondition(andNode, mapOf("adherence_rate" to 85.0, "completion_rate" to 50.0, "exception_count" to 5.0))).isFalse()
    }

    // ── JSON parsing ──

    @Test
    fun `parseCondition handles simple metric JSON`() {
        val json = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}"""
        val condition = useCase.parseCondition(json)
        assertThat(condition.type).isEqualTo("metric")
        assertThat(condition.metricType).isEqualTo("adherence_rate")
        assertThat(condition.operator).isEqualTo(">=")
        assertThat(condition.threshold).isEqualTo(80.0)
    }

    @Test
    fun `parseCondition handles compound JSON`() {
        val json = """{"type":"and","children":[{"type":"metric","metricType":"a","operator":">=","threshold":80.0},{"type":"metric","metricType":"b","operator":"<","threshold":5.0}]}"""
        val condition = useCase.parseCondition(json)
        assertThat(condition.type).isEqualTo("and")
        assertThat(condition.children).hasSize(2)
    }

    @Test
    fun `parseCondition handles malformed JSON gracefully`() {
        val condition = useCase.parseCondition("not json")
        assertThat(condition.type).isEqualTo("metric")
    }

}

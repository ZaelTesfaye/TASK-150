package com.nutriops.app.domain.usecase.rules

import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import com.nutriops.app.security.RbacManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Rules engine evaluator supporting:
 * - Compound conditions: A AND (B OR C)
 * - Hysteresis: enter at threshold X%, exit at Y%
 * - Minimum duration: condition must hold for N minutes
 * - Effective time windows
 * - Rule versioning
 * - Back-calculation for any historical date range
 */
class EvaluateRuleUseCase @Inject constructor(
    private val ruleRepository: RuleRepository
) {
    data class EvaluationResult(
        val ruleId: String,
        val ruleName: String,
        val triggered: Boolean,
        val reason: String,
        val metricValue: Double?,
        val threshold: Double?,
        val version: Long,
        val evaluatedAt: String
    )

    data class ConditionNode(
        val type: String,        // "metric", "and", "or"
        val metricType: String?, // for leaf nodes
        val operator: String?,   // ">", "<", ">=", "<=", "=="
        val threshold: Double?,  // threshold value
        val children: List<ConditionNode>? // for compound nodes
    )

    /**
     * Evaluate all active rules against current metrics for a user.
     * Runs off the main thread on Dispatchers.Default.
     */
    suspend fun evaluateAllRules(
        userId: String,
        metricsMap: Map<String, Double>,
        actorId: String,
        actorRole: Role
    ): Result<List<EvaluationResult>> = withContext(Dispatchers.Default) {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.EVALUATE_RULES)
            .getOrElse { return@withContext Result.failure(it) }

        try {
            val rules = ruleRepository.getAllActiveRules()
            val now = LocalDateTime.now()
            val results = mutableListOf<EvaluationResult>()

            for (rule in rules) {
                // Check effective window
                if (!isInEffectiveWindow(rule.effectiveWindowStart, rule.effectiveWindowEnd, now)) {
                    continue
                }

                val condition = parseCondition(rule.conditionsJson)
                val triggered = evaluateCondition(condition, metricsMap)

                // Apply hysteresis
                val lastMetric = ruleRepository.getLatestMetric(userId, rule.id)
                val wasTriggered = lastMetric != null && lastMetric.metricValue >= rule.hysteresisEnterPercent

                val effectiveTriggered = if (wasTriggered) {
                    // Already triggered: must fall below exit threshold to un-trigger
                    val primaryMetric = getPrimaryMetricValue(condition, metricsMap)
                    primaryMetric == null || primaryMetric >= rule.hysteresisExitPercent
                } else {
                    triggered
                }

                // Minimum-duration hold enforcement
                val durationSatisfied = if (effectiveTriggered && rule.minimumDurationMinutes > 0) {
                    val hold = ruleRepository.getConditionHold(rule.id, userId)
                    if (hold == null) {
                        // First time condition is met: record hold start, do NOT trigger yet
                        ruleRepository.upsertConditionHold(
                            rule.id, userId, now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        )
                        false
                    } else {
                        val holdStart = LocalDateTime.parse(hold.holdStartedAt)
                        val minutesHeld = ChronoUnit.MINUTES.between(holdStart, now)
                        minutesHeld >= rule.minimumDurationMinutes
                    }
                } else {
                    if (!effectiveTriggered) {
                        // Condition no longer met: clear any hold
                        ruleRepository.deleteConditionHold(rule.id, userId)
                    }
                    effectiveTriggered
                }

                results.add(
                    EvaluationResult(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        triggered = durationSatisfied,
                        reason = when {
                            durationSatisfied -> "Conditions met with hysteresis and minimum duration"
                            effectiveTriggered -> "Conditions met but minimum hold duration (${rule.minimumDurationMinutes}min) not yet reached"
                            else -> "Conditions not met"
                        },
                        metricValue = getPrimaryMetricValue(condition, metricsMap),
                        threshold = rule.hysteresisEnterPercent,
                        version = rule.currentVersion,
                        evaluatedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    )
                )
            }

            Result.success(results)
        } catch (e: Exception) {
            AppLogger.error("RulesEngine", "Rule evaluation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Back-calculate: replay rule evaluation using historical metrics and
     * the rule version that was active at that time.
     */
    suspend fun backCalculate(
        ruleId: String,
        userId: String,
        startDate: String,
        endDate: String,
        actorId: String,
        actorRole: Role
    ): Result<List<EvaluationResult>> = withContext(Dispatchers.Default) {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.BACK_CALCULATE)
            .getOrElse { return@withContext Result.failure(it) }

        try {
            val metrics = ruleRepository.getMetricsByRuleAndDateRange(ruleId, startDate, endDate)
            val ruleVersions = ruleRepository.getRuleVersions(ruleId)

            val results = mutableListOf<EvaluationResult>()

            for (metric in metrics) {
                // Find the rule version active at the metric's snapshot date
                val applicableVersion = ruleVersions
                    .filter { it.createdAt <= metric.snapshotDate }
                    .maxByOrNull { it.version }
                    ?: continue

                val condition = parseCondition(applicableVersion.conditionsJson)
                val metricsMap = mapOf(metric.metricType to metric.metricValue)
                val triggered = evaluateCondition(condition, metricsMap)

                results.add(
                    EvaluationResult(
                        ruleId = ruleId,
                        ruleName = "Historical",
                        triggered = triggered,
                        reason = "Back-calculated with rule v${applicableVersion.version}",
                        metricValue = metric.metricValue,
                        threshold = applicableVersion.hysteresisEnterPercent,
                        version = applicableVersion.version,
                        evaluatedAt = metric.snapshotDate
                    )
                )
            }

            Result.success(results)
        } catch (e: Exception) {
            AppLogger.error("RulesEngine", "Back-calculation failed", e)
            Result.failure(e)
        }
    }

    fun parseCondition(json: String): ConditionNode {
        return try {
            val obj = JSONObject(json)
            parseConditionObject(obj)
        } catch (e: Exception) {
            ConditionNode("metric", "default", ">=", 0.0, null)
        }
    }

    private fun parseConditionObject(obj: JSONObject): ConditionNode {
        val type = obj.optString("type", "metric")
        return when (type) {
            "and", "or" -> {
                val children = obj.getJSONArray("children")
                val childNodes = (0 until children.length()).map { parseConditionObject(children.getJSONObject(it)) }
                ConditionNode(type, null, null, null, childNodes)
            }
            else -> {
                ConditionNode(
                    type = "metric",
                    metricType = obj.optString("metricType", "default"),
                    operator = obj.optString("operator", ">="),
                    threshold = obj.optDouble("threshold", 0.0),
                    children = null
                )
            }
        }
    }

    fun evaluateCondition(node: ConditionNode, metrics: Map<String, Double>): Boolean {
        return when (node.type) {
            "and" -> node.children?.all { evaluateCondition(it, metrics) } ?: false
            "or" -> node.children?.any { evaluateCondition(it, metrics) } ?: false
            else -> {
                val value = metrics[node.metricType] ?: return false
                val threshold = node.threshold ?: return false
                when (node.operator) {
                    ">" -> value > threshold
                    "<" -> value < threshold
                    ">=" -> value >= threshold
                    "<=" -> value <= threshold
                    "==" -> value == threshold
                    "!=" -> value != threshold
                    else -> false
                }
            }
        }
    }

    private fun isInEffectiveWindow(start: String?, end: String?, now: LocalDateTime): Boolean {
        if (start == null || end == null) return true
        return try {
            val windowStart = LocalDateTime.parse(start)
            val windowEnd = LocalDateTime.parse(end)
            now in windowStart..windowEnd
        } catch (_: Exception) {
            true
        }
    }

    private fun getPrimaryMetricValue(condition: ConditionNode, metrics: Map<String, Double>): Double? {
        return when (condition.type) {
            "metric" -> metrics[condition.metricType]
            "and", "or" -> condition.children?.firstOrNull()?.let { getPrimaryMetricValue(it, metrics) }
            else -> null
        }
    }
}

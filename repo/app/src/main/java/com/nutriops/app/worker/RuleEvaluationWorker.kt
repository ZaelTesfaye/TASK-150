package com.nutriops.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import com.nutriops.app.logging.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Background worker for periodic rule evaluation.
 * Runs off the main thread using coroutines with IO/Default dispatchers.
 * Evaluates adherence, exceptions, and operational KPI rules for all active users.
 */
@HiltWorker
class RuleEvaluationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val evaluateRuleUseCase: EvaluateRuleUseCase,
    private val ruleRepository: RuleRepository,
    private val userRepository: UserRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // If a specific userId was provided (e.g. one-shot work), evaluate just that user.
            // Otherwise iterate all active users (periodic work).
            val specificUserId = inputData.getString("userId")

            if (specificUserId != null) {
                evaluateForUser(specificUserId, inputData.getString("actorId") ?: specificUserId)
            } else {
                val allUsers = userRepository.getAllUsers()
                val activeUsers = allUsers.filter { it.isActive == 1L }
                AppLogger.info("RuleWorker", "Starting rule evaluation for ${activeUsers.size} active users")

                for (user in activeUsers) {
                    evaluateForUser(user.id, user.id)
                }
            }

            Result.success()
        } catch (e: Exception) {
            AppLogger.error("RuleWorker", "Worker failed", e)
            Result.retry()
        }
    }

    private suspend fun evaluateForUser(userId: String, actorId: String) {
        val now = LocalDateTime.now()
        val thirtyDaysAgo = now.minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val snapshots = ruleRepository.getMetricsByUserAndDateRange(userId, thirtyDaysAgo, nowStr)

        // Build metrics map from the latest snapshot per metric type
        val metricsMap = snapshots
            .groupBy { it.metricType }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.snapshotDate }!!.metricValue }

        if (metricsMap.isEmpty()) {
            AppLogger.info("RuleWorker", "No metrics found for user $userId, skipping")
            return
        }

        val results = evaluateRuleUseCase.evaluateAllRules(
            userId, metricsMap, actorId, Role.ADMINISTRATOR
        )

        results.onSuccess { evaluations ->
            val triggered = evaluations.count { it.triggered }
            AppLogger.info("RuleWorker", "Evaluated ${evaluations.size} rules, $triggered triggered for user $userId")

            // Record evaluation results as metric snapshots for future reference
            for (eval in evaluations) {
                if (eval.metricValue != null) {
                    ruleRepository.recordMetric(
                        userId = userId,
                        ruleId = eval.ruleId,
                        metricType = "rule_eval_${eval.ruleId}",
                        metricValue = eval.metricValue,
                        snapshotDate = eval.evaluatedAt,
                        metadata = """{"triggered":${eval.triggered},"reason":"${eval.reason}","version":${eval.version}}"""
                    )
                }
            }
        }.onFailure { error ->
            AppLogger.error("RuleWorker", "Rule evaluation failed for user $userId", error)
        }
    }

    companion object {
        const val WORK_NAME = "rule_evaluation"
    }
}

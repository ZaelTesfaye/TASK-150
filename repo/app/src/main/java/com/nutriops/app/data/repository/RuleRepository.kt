package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val ruleQueries get() = database.rulesQueries
    private val metricsQueries get() = database.metricsSnapshotsQueries

    suspend fun createRule(
        name: String,
        description: String,
        ruleType: String,
        conditionsJson: String,
        hysteresisEnter: Double,
        hysteresisExit: Double,
        minimumDurationMinutes: Long,
        effectiveWindowStart: String?,
        effectiveWindowEnd: String?,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ruleId = UUID.randomUUID().toString()
            val versionId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Rule", entityId = ruleId,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"name":"$name","type":"$ruleType","version":1}"""
            ) {
                ruleQueries.insertRule(
                    ruleId, name, description, ruleType, conditionsJson,
                    hysteresisEnter, hysteresisExit, minimumDurationMinutes,
                    effectiveWindowStart, effectiveWindowEnd,
                    1, 1, actorId, now, now
                )
                ruleQueries.insertRuleVersion(
                    versionId, ruleId, 1, conditionsJson,
                    hysteresisEnter, hysteresisExit, minimumDurationMinutes,
                    effectiveWindowStart, effectiveWindowEnd, actorId, now
                )
            }

            Result.success(ruleId)
        } catch (e: Exception) {
            AppLogger.error("RuleRepo", "Failed to create rule", e)
            Result.failure(e)
        }
    }

    suspend fun updateRule(
        ruleId: String,
        name: String,
        description: String,
        ruleType: String,
        conditionsJson: String,
        hysteresisEnter: Double,
        hysteresisExit: Double,
        minimumDurationMinutes: Long,
        effectiveWindowStart: String?,
        effectiveWindowEnd: String?,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val rule = ruleQueries.getRuleById(ruleId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Rule not found"))

            val newVersion = rule.currentVersion + 1
            val versionId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Rule", entityId = ruleId,
                action = AuditAction.UPDATE, actorId = actorId, actorRole = actorRole,
                previousState = """{"version":${rule.currentVersion}}""",
                newState = """{"version":$newVersion}"""
            ) {
                ruleQueries.updateRule(
                    name, description, ruleType, conditionsJson,
                    hysteresisEnter, hysteresisExit, minimumDurationMinutes,
                    effectiveWindowStart, effectiveWindowEnd,
                    newVersion, 1, now, ruleId
                )
                ruleQueries.insertRuleVersion(
                    versionId, ruleId, newVersion, conditionsJson,
                    hysteresisEnter, hysteresisExit, minimumDurationMinutes,
                    effectiveWindowStart, effectiveWindowEnd, actorId, now
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("RuleRepo", "Failed to update rule", e)
            Result.failure(e)
        }
    }

    suspend fun getRuleById(id: String) = withContext(Dispatchers.IO) {
        ruleQueries.getRuleById(id).executeAsOneOrNull()
    }

    suspend fun getAllActiveRules() = withContext(Dispatchers.IO) {
        ruleQueries.getAllActiveRules().executeAsList()
    }

    suspend fun getRulesByType(type: String) = withContext(Dispatchers.IO) {
        ruleQueries.getRulesByType(type).executeAsList()
    }

    suspend fun getRuleVersions(ruleId: String) = withContext(Dispatchers.IO) {
        ruleQueries.getRuleVersionsByRuleId(ruleId).executeAsList()
    }

    suspend fun getRuleVersion(ruleId: String, version: Long) = withContext(Dispatchers.IO) {
        ruleQueries.getRuleVersionByRuleIdAndVersion(ruleId, version).executeAsOneOrNull()
    }

    // ── Metrics ──

    suspend fun recordMetric(
        userId: String, ruleId: String?, metricType: String,
        metricValue: Double, snapshotDate: String, metadata: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            metricsQueries.insertMetricsSnapshot(id, userId, ruleId, metricType, metricValue, snapshotDate, metadata, now)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMetricsByUserAndDateRange(userId: String, startDate: String, endDate: String) =
        withContext(Dispatchers.IO) {
            metricsQueries.getSnapshotsByUserAndDateRange(userId, startDate, endDate).executeAsList()
        }

    suspend fun getMetricsByRuleAndDateRange(ruleId: String, startDate: String, endDate: String) =
        withContext(Dispatchers.IO) {
            metricsQueries.getSnapshotsByRuleAndDateRange(ruleId, startDate, endDate).executeAsList()
        }

    suspend fun getLatestMetric(userId: String, ruleId: String) = withContext(Dispatchers.IO) {
        metricsQueries.getLatestSnapshotForUserAndRule(userId, ruleId).executeAsOneOrNull()
    }

    // ── Condition Hold Tracking ──

    suspend fun getConditionHold(ruleId: String, userId: String) = withContext(Dispatchers.IO) {
        ruleQueries.getConditionHold(ruleId, userId).executeAsOneOrNull()
    }

    suspend fun upsertConditionHold(ruleId: String, userId: String, holdStartedAt: String) = withContext(Dispatchers.IO) {
        ruleQueries.upsertConditionHold(ruleId, userId, holdStartedAt)
    }

    suspend fun deleteConditionHold(ruleId: String, userId: String) = withContext(Dispatchers.IO) {
        ruleQueries.deleteConditionHold(ruleId, userId)
    }
}

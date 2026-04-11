package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.RolloutStatus
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RolloutRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val queries get() = database.rolloutsQueries
    private val userQueries get() = database.usersQueries

    /**
     * Deterministic canary assignment: sort all END_USER ids alphabetically,
     * take the first N% as canary users.
     */
    suspend fun createRollout(
        configVersionId: String,
        canaryPercentage: Int,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rolloutId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val allEndUsers = userQueries.getUsersByRole("END_USER").executeAsList()
            val sortedIds = allEndUsers.map { it.id }.sorted()
            val canaryCount = (sortedIds.size * canaryPercentage / 100).coerceAtLeast(1)
            val canaryUserIds = sortedIds.take(canaryCount)
            val canaryJson = canaryUserIds.joinToString(",", "[", "]") { "\"$it\"" }

            auditManager.logWithTransaction(
                entityType = "Rollout", entityId = rolloutId,
                action = AuditAction.ROLLOUT_START, actorId = actorId, actorRole = actorRole,
                details = """{"configVersionId":"$configVersionId","canaryPercent":$canaryPercentage,"canaryCount":$canaryCount,"totalUsers":${sortedIds.size}}"""
            ) {
                queries.insertRollout(
                    id = rolloutId,
                    configVersionId = configVersionId,
                    canaryPercentage = canaryPercentage.toLong(),
                    status = RolloutStatus.CANARY.name,
                    totalUsers = sortedIds.size.toLong(),
                    canaryUsers = canaryJson,
                    createdBy = actorId,
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("RolloutRepo", "Canary rollout started: $rolloutId ($canaryCount/${sortedIds.size} users)")
            Result.success(rolloutId)
        } catch (e: Exception) {
            AppLogger.error("RolloutRepo", "Failed to create rollout", e)
            Result.failure(e)
        }
    }

    suspend fun promoteToFull(
        rolloutId: String, actorId: String, actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val rollout = queries.getRolloutById(rolloutId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Rollout not found"))

            if (rollout.status != RolloutStatus.CANARY.name) {
                return@withContext Result.failure(
                    IllegalStateException("Can only promote CANARY rollouts")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Rollout", entityId = rolloutId,
                action = AuditAction.ROLLOUT_COMPLETE, actorId = actorId, actorRole = actorRole,
                previousState = """{"status":"CANARY"}""",
                newState = """{"status":"FULL"}"""
            ) {
                queries.updateRolloutStatus(RolloutStatus.FULL.name, now, rolloutId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rollback(
        rolloutId: String, actorId: String, actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Rollout", entityId = rolloutId,
                action = AuditAction.ROLLOUT_ROLLBACK, actorId = actorId, actorRole = actorRole,
                details = """{"event":"rollback"}"""
            ) {
                queries.updateRolloutStatus(RolloutStatus.ROLLED_BACK.name, now, rolloutId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isUserInCanary(userId: String, rolloutId: String): Boolean {
        val rollout = queries.getRolloutById(rolloutId).executeAsOneOrNull() ?: return false
        return rollout.canaryUsers.contains(userId)
    }

    suspend fun getActiveRollout() = withContext(Dispatchers.IO) {
        queries.getActiveRollout().executeAsOneOrNull()
    }

    suspend fun getRolloutById(id: String) = withContext(Dispatchers.IO) {
        queries.getRolloutById(id).executeAsOneOrNull()
    }
}

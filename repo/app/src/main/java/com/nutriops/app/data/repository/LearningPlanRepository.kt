package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearningPlanRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val queries get() = database.learningPlansQueries

    suspend fun createLearningPlan(
        userId: String,
        title: String,
        description: String,
        startDate: String,
        endDate: String,
        frequencyPerWeek: Long,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            try {
                val start = LocalDate.parse(startDate)
                val end = LocalDate.parse(endDate)
                if (end.isBefore(start)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("End date cannot be earlier than start date")
                    )
                }
            } catch (e: java.time.format.DateTimeParseException) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid date format: use YYYY-MM-DD")
                )
            }
            if (frequencyPerWeek < 1 || frequencyPerWeek > 7) {
                return@withContext Result.failure(
                    IllegalArgumentException("Frequency must be between 1 and 7 days/week")
                )
            }

            val planId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "LearningPlan",
                entityId = planId,
                action = AuditAction.CREATE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"userId":"$userId","title":"$title","frequency":$frequencyPerWeek}"""
            ) {
                queries.insertLearningPlan(
                    id = planId,
                    userId = userId,
                    title = title,
                    description = description,
                    startDate = startDate,
                    endDate = endDate,
                    frequencyPerWeek = frequencyPerWeek,
                    status = LearningPlanStatus.NOT_STARTED.name,
                    parentPlanId = null,
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("LearningPlanRepo", "Learning plan created: $planId")
            Result.success(planId)
        } catch (e: Exception) {
            AppLogger.error("LearningPlanRepo", "Failed to create learning plan", e)
            Result.failure(e)
        }
    }

    suspend fun transitionStatus(
        planId: String,
        newStatus: LearningPlanStatus,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val plan = queries.getLearningPlanById(planId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Learning plan not found"))

            val currentStatus = LearningPlanStatus.valueOf(plan.status)
            if (!currentStatus.canTransitionTo(newStatus)) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot transition from ${currentStatus.name} to ${newStatus.name}")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "LearningPlan",
                entityId = planId,
                action = AuditAction.STATUS_CHANGE,
                actorId = actorId,
                actorRole = actorRole,
                previousState = """{"status":"${currentStatus.name}"}""",
                newState = """{"status":"${newStatus.name}"}"""
            ) {
                queries.updateLearningPlanStatus(
                    status = newStatus.name,
                    updatedAt = now,
                    id = planId
                )
            }

            AppLogger.info("LearningPlanRepo", "Plan $planId transitioned: ${currentStatus.name} -> ${newStatus.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("LearningPlanRepo", "Failed to transition learning plan", e)
            Result.failure(e)
        }
    }

    /**
     * Completed plans cannot be edited; they must be duplicated first.
     * Creates a new plan with NOT_STARTED status, referencing the parent plan.
     */
    suspend fun duplicatePlan(
        planId: String,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val plan = queries.getLearningPlanById(planId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Learning plan not found"))

            if (plan.status != LearningPlanStatus.COMPLETED.name) {
                return@withContext Result.failure(
                    IllegalStateException("Only completed plans can be duplicated for editing")
                )
            }

            val newPlanId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "LearningPlan",
                entityId = newPlanId,
                action = AuditAction.DUPLICATE_PLAN,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"parentPlanId":"$planId","originalTitle":"${plan.title}"}"""
            ) {
                queries.insertLearningPlan(
                    id = newPlanId,
                    userId = plan.userId,
                    title = "${plan.title} (Copy)",
                    description = plan.description,
                    startDate = plan.startDate,
                    endDate = plan.endDate,
                    frequencyPerWeek = plan.frequencyPerWeek,
                    status = LearningPlanStatus.NOT_STARTED.name,
                    parentPlanId = planId,
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("LearningPlanRepo", "Plan duplicated: $planId -> $newPlanId")
            Result.success(newPlanId)
        } catch (e: Exception) {
            AppLogger.error("LearningPlanRepo", "Failed to duplicate plan", e)
            Result.failure(e)
        }
    }

    suspend fun getLearningPlanById(id: String) = withContext(Dispatchers.IO) {
        queries.getLearningPlanById(id).executeAsOneOrNull()
    }

    suspend fun getLearningPlansByUserId(userId: String) = withContext(Dispatchers.IO) {
        queries.getLearningPlansByUserId(userId).executeAsList()
    }

    suspend fun getLearningPlansByStatus(userId: String, status: LearningPlanStatus) =
        withContext(Dispatchers.IO) {
            queries.getLearningPlansByStatus(userId, status.name).executeAsList()
        }
}

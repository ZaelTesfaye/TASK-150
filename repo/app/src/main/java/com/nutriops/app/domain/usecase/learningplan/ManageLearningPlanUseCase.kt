package com.nutriops.app.domain.usecase.learningplan

import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.RbacManager
import java.time.LocalDate
import javax.inject.Inject

/**
 * Learning Plan lifecycle management with:
 * - Status transition matrix enforcement
 * - Confirmation requirement on status changes
 * - Duplicate-before-edit for completed plans
 * - Frequency constraint validation (1-7 days/week)
 * - Date validation (end >= start)
 */
class ManageLearningPlanUseCase @Inject constructor(
    private val learningPlanRepository: LearningPlanRepository
) {
    suspend fun createPlan(
        userId: String,
        title: String,
        description: String,
        startDate: String,
        endDate: String,
        frequencyPerWeek: Int,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.CREATE_LEARNING_PLAN)
            .getOrElse { return Result.failure(it) }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return Result.failure(it) }

        if (title.isBlank()) {
            return Result.failure(IllegalArgumentException("Title is required"))
        }
        if (frequencyPerWeek !in 1..7) {
            return Result.failure(IllegalArgumentException("Frequency must be between 1 and 7 days/week"))
        }
        try {
            val start = LocalDate.parse(startDate)
            val end = LocalDate.parse(endDate)
            if (end.isBefore(start)) {
                return Result.failure(IllegalArgumentException("End date cannot be earlier than start date"))
            }
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid date format: use YYYY-MM-DD"))
        }

        return learningPlanRepository.createLearningPlan(
            userId, title, description, startDate, endDate,
            frequencyPerWeek.toLong(), actorId, actorRole
        )
    }

    /**
     * Transition with status matrix enforcement.
     * Valid transitions:
     * NOT_STARTED -> IN_PROGRESS
     * IN_PROGRESS -> PAUSED | COMPLETED
     * PAUSED -> IN_PROGRESS | COMPLETED
     * COMPLETED -> ARCHIVED
     */
    suspend fun transitionStatus(
        planId: String,
        newStatus: LearningPlanStatus,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.TRANSITION_LEARNING_PLAN)
            .getOrElse { return Result.failure(it) }

        val plan = learningPlanRepository.getLearningPlanById(planId)
            ?: return Result.failure(IllegalArgumentException("Learning plan not found"))
        RbacManager.checkObjectOwnership(actorId, plan.userId, actorRole)
            .getOrElse { return Result.failure(it) }

        return learningPlanRepository.transitionStatus(planId, newStatus, actorId, actorRole)
    }

    /**
     * Completed plans cannot be edited; they must be duplicated first.
     * Returns the new plan ID.
     */
    suspend fun duplicateForEditing(
        planId: String,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.DUPLICATE_LEARNING_PLAN)
            .getOrElse { return Result.failure(it) }

        val plan = learningPlanRepository.getLearningPlanById(planId)
            ?: return Result.failure(IllegalArgumentException("Learning plan not found"))
        RbacManager.checkObjectOwnership(actorId, plan.userId, actorRole)
            .getOrElse { return Result.failure(it) }

        return learningPlanRepository.duplicatePlan(planId, actorId, actorRole)
    }

    suspend fun getPlans(userId: String, actorId: String, actorRole: Role) =
        learningPlanRepository.getLearningPlansByUserId(userId)

    suspend fun getPlanById(planId: String) =
        learningPlanRepository.getLearningPlanById(planId)

    /**
     * Returns allowed transitions from the current status
     */
    fun getAllowedTransitions(currentStatus: LearningPlanStatus): Set<LearningPlanStatus> =
        currentStatus.allowedTransitions()
}

package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
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
class MealPlanRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val planQueries get() = database.mealPlansQueries
    private val mealQueries get() = database.mealsQueries
    private val swapQueries get() = database.swapMappingsQueries

    suspend fun createMealPlan(
        userId: String,
        weekStartDate: String,
        weekEndDate: String,
        dailyCalorieBudget: Long,
        proteinTarget: Long,
        carbTarget: Long,
        fatTarget: Long,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            try {
                val start = LocalDate.parse(weekStartDate)
                val end = LocalDate.parse(weekEndDate)
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

            val planId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "MealPlan",
                entityId = planId,
                action = AuditAction.CREATE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"userId":"$userId","weekStart":"$weekStartDate","calories":$dailyCalorieBudget}"""
            ) {
                planQueries.insertMealPlan(
                    id = planId,
                    userId = userId,
                    weekStartDate = weekStartDate,
                    weekEndDate = weekEndDate,
                    dailyCalorieBudget = dailyCalorieBudget,
                    proteinTargetGrams = proteinTarget,
                    carbTargetGrams = carbTarget,
                    fatTargetGrams = fatTarget,
                    status = "ACTIVE",
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("MealPlanRepo", "Meal plan created: $planId for user: $userId")
            Result.success(planId)
        } catch (e: Exception) {
            AppLogger.error("MealPlanRepo", "Failed to create meal plan", e)
            Result.failure(e)
        }
    }

    suspend fun addMeal(
        mealPlanId: String,
        dayOfWeek: Long,
        mealTime: String,
        name: String,
        description: String,
        calories: Long,
        proteinGrams: Double,
        carbGrams: Double,
        fatGrams: Double,
        reasons: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mealId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            mealQueries.insertMeal(
                id = mealId,
                mealPlanId = mealPlanId,
                dayOfWeek = dayOfWeek,
                mealTime = mealTime,
                name = name,
                description = description,
                calories = calories,
                proteinGrams = proteinGrams,
                carbGrams = carbGrams,
                fatGrams = fatGrams,
                reasons = reasons,
                isSwapped = 0,
                originalMealId = null,
                createdAt = now
            )

            Result.success(mealId)
        } catch (e: Exception) {
            AppLogger.error("MealPlanRepo", "Failed to add meal", e)
            Result.failure(e)
        }
    }

    suspend fun addSwapMapping(
        originalMealId: String,
        swapMealName: String,
        swapDescription: String,
        swapCalories: Long,
        swapProteinGrams: Double,
        swapCarbGrams: Double,
        swapFatGrams: Double,
        caloriesDiffPercent: Double,
        proteinDiffGrams: Double,
        isWithinTolerance: Boolean,
        swapReasons: String,
        rankScore: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val swapId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            swapQueries.insertSwapMapping(
                id = swapId,
                originalMealId = originalMealId,
                swapMealName = swapMealName,
                swapDescription = swapDescription,
                swapCalories = swapCalories,
                swapProteinGrams = swapProteinGrams,
                swapCarbGrams = swapCarbGrams,
                swapFatGrams = swapFatGrams,
                caloriesDiffPercent = caloriesDiffPercent,
                proteinDiffGrams = proteinDiffGrams,
                isWithinTolerance = if (isWithinTolerance) 1 else 0,
                swapReasons = swapReasons,
                rankScore = rankScore,
                createdAt = now
            )

            Result.success(swapId)
        } catch (e: Exception) {
            AppLogger.error("MealPlanRepo", "Failed to add swap mapping", e)
            Result.failure(e)
        }
    }

    suspend fun performSwap(
        originalMealId: String,
        swapMappingId: String,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val originalMeal = mealQueries.getMealById(originalMealId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Original meal not found"))
            val swap = swapQueries.getSwapById(swapMappingId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Swap mapping not found"))

            if (swap.isWithinTolerance != 1L) {
                return@withContext Result.failure(
                    IllegalArgumentException("Swap is outside tolerance bounds")
                )
            }

            val newMealId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Meal",
                entityId = newMealId,
                action = AuditAction.SWAP_MEAL,
                actorId = actorId,
                actorRole = actorRole,
                previousState = """{"mealId":"$originalMealId","name":"${originalMeal.name}","calories":${originalMeal.calories}}""",
                newState = """{"mealId":"$newMealId","name":"${swap.swapMealName}","calories":${swap.swapCalories}}""",
                details = """{"swapMappingId":"$swapMappingId","calorieDiff":"${swap.caloriesDiffPercent}%","proteinDiff":"${swap.proteinDiffGrams}g"}"""
            ) {
                mealQueries.insertMeal(
                    id = newMealId,
                    mealPlanId = originalMeal.mealPlanId,
                    dayOfWeek = originalMeal.dayOfWeek,
                    mealTime = originalMeal.mealTime,
                    name = swap.swapMealName,
                    description = swap.swapDescription,
                    calories = swap.swapCalories,
                    proteinGrams = swap.swapProteinGrams,
                    carbGrams = swap.swapCarbGrams,
                    fatGrams = swap.swapFatGrams,
                    reasons = swap.swapReasons,
                    isSwapped = 1,
                    originalMealId = originalMealId,
                    createdAt = now
                )

                mealQueries.deleteMeal(originalMealId)
            }

            Result.success(newMealId)
        } catch (e: Exception) {
            AppLogger.error("MealPlanRepo", "Failed to perform swap", e)
            Result.failure(e)
        }
    }

    suspend fun getMealPlanById(id: String) = withContext(Dispatchers.IO) {
        planQueries.getMealPlanById(id).executeAsOneOrNull()
    }

    suspend fun getMealPlansByUserId(userId: String) = withContext(Dispatchers.IO) {
        planQueries.getMealPlansByUserId(userId).executeAsList()
    }

    suspend fun getActivePlanForUser(userId: String) = withContext(Dispatchers.IO) {
        planQueries.getActiveMealPlanForUser(userId).executeAsOneOrNull()
    }

    suspend fun getMealsByPlanId(planId: String) = withContext(Dispatchers.IO) {
        mealQueries.getMealsByPlanId(planId).executeAsList()
    }

    suspend fun getMealsByPlanAndDay(planId: String, dayOfWeek: Long) = withContext(Dispatchers.IO) {
        mealQueries.getMealsByPlanAndDay(planId, dayOfWeek).executeAsList()
    }

    suspend fun getDailyCalories(planId: String, dayOfWeek: Long) = withContext(Dispatchers.IO) {
        mealQueries.getDailyCalories(planId, dayOfWeek).executeAsOneOrNull()
    }

    suspend fun getSwapsForMeal(mealId: String) = withContext(Dispatchers.IO) {
        swapQueries.getSwapsByOriginalMealId(mealId).executeAsList()
    }

    suspend fun getMealPlanForMeal(mealId: String) = withContext(Dispatchers.IO) {
        val meal = mealQueries.getMealById(mealId).executeAsOneOrNull() ?: return@withContext null
        planQueries.getMealPlanById(meal.mealPlanId).executeAsOneOrNull()
    }
}

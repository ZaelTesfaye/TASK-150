package com.nutriops.app.domain.usecase.mealplan

import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

/**
 * One-click meal swap within tolerance bounds:
 * - ±10% calories
 * - ±5g protein
 *
 * Only swaps marked as within tolerance are permitted.
 * Swap ranking is deterministic (closest match scores highest).
 */
class SwapMealUseCase @Inject constructor(
    private val mealPlanRepository: MealPlanRepository
) {
    private suspend fun verifySwapBelongsToMeal(swapMappingId: String, originalMealId: String): Result<Unit> {
        val swaps = mealPlanRepository.getSwapsForMeal(originalMealId)
        if (swaps.none { it.id == swapMappingId }) {
            return Result.failure(SecurityException("Swap mapping does not belong to the specified meal"))
        }
        return Result.success(Unit)
    }

    private suspend fun verifyMealOwnership(mealId: String, actorId: String, actorRole: Role): Result<Unit> {
        if (actorRole == Role.ADMINISTRATOR) return Result.success(Unit)
        val meal = mealPlanRepository.getMealPlanForMeal(mealId)
            ?: return Result.failure(SecurityException("Meal not found"))
        return RbacManager.checkObjectOwnership(actorId, meal.userId, actorRole)
    }

    suspend fun getAvailableSwaps(mealId: String, actorId: String, actorRole: Role): Result<List<SwapOption>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.SWAP_MEAL)
            .getOrElse { return Result.failure(it) }
        verifyMealOwnership(mealId, actorId, actorRole)
            .getOrElse { return Result.failure(it) }

        val swaps = mealPlanRepository.getSwapsForMeal(mealId)
        return Result.success(swaps.map { swap ->
            SwapOption(
                swapId = swap.id,
                name = swap.swapMealName,
                description = swap.swapDescription,
                calories = swap.swapCalories.toInt(),
                proteinGrams = swap.swapProteinGrams,
                carbGrams = swap.swapCarbGrams,
                fatGrams = swap.swapFatGrams,
                caloriesDiffPercent = swap.caloriesDiffPercent,
                proteinDiffGrams = swap.proteinDiffGrams,
                rankScore = swap.rankScore,
                reasons = swap.swapReasons
            )
        })
    }

    suspend fun executeSwap(
        originalMealId: String,
        swapMappingId: String,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.SWAP_MEAL)
            .getOrElse { return Result.failure(it) }
        verifyMealOwnership(originalMealId, actorId, actorRole)
            .getOrElse { return Result.failure(it) }

        // Verify the swap mapping actually belongs to the original meal (prevent IDOR)
        verifySwapBelongsToMeal(swapMappingId, originalMealId)
            .getOrElse { return Result.failure(it) }

        return mealPlanRepository.performSwap(originalMealId, swapMappingId, actorId, actorRole)
    }

    data class SwapOption(
        val swapId: String,
        val name: String,
        val description: String,
        val calories: Int,
        val proteinGrams: Double,
        val carbGrams: Double,
        val fatGrams: Double,
        val caloriesDiffPercent: Double,
        val proteinDiffGrams: Double,
        val rankScore: Double,
        val reasons: String
    )
}

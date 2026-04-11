package com.nutriops.app.domain.usecase.profile

import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

class ManageProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository
) {
    suspend fun createProfile(
        userId: String,
        ageRange: AgeRange,
        dietaryPattern: DietaryPattern,
        allergies: List<String>,
        goal: HealthGoal,
        preferredMealTimes: List<MealTime>,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.EDIT_OWN_PROFILE).getOrElse { return Result.failure(it) }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole).getOrElse { return Result.failure(it) }

        val macros = calculateMacros(goal, ageRange)

        return profileRepository.createProfile(
            userId = userId,
            ageRange = ageRange.display,
            dietaryPattern = dietaryPattern.name,
            allergies = allergies.joinToString(",", "[", "]") { "\"$it\"" },
            goal = goal.name,
            preferredMealTimes = preferredMealTimes.joinToString(",", "[", "]") { "\"${it.name}\"" },
            dailyCalorieBudget = macros.calories.toLong(),
            proteinTargetGrams = macros.proteinGrams.toLong(),
            carbTargetGrams = macros.carbGrams.toLong(),
            fatTargetGrams = macros.fatGrams.toLong(),
            actorId = actorId,
            actorRole = actorRole
        )
    }

    suspend fun updateProfile(
        profileId: String,
        userId: String,
        ageRange: AgeRange,
        dietaryPattern: DietaryPattern,
        allergies: List<String>,
        goal: HealthGoal,
        preferredMealTimes: List<MealTime>,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.EDIT_OWN_PROFILE).getOrElse { return Result.failure(it) }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole).getOrElse { return Result.failure(it) }

        val macros = calculateMacros(goal, ageRange)

        return profileRepository.updateProfile(
            profileId = profileId,
            ageRange = ageRange.display,
            dietaryPattern = dietaryPattern.name,
            allergies = allergies.joinToString(",", "[", "]") { "\"$it\"" },
            goal = goal.name,
            preferredMealTimes = preferredMealTimes.joinToString(",", "[", "]") { "\"${it.name}\"" },
            dailyCalorieBudget = macros.calories.toLong(),
            proteinTargetGrams = macros.proteinGrams.toLong(),
            carbTargetGrams = macros.carbGrams.toLong(),
            fatTargetGrams = macros.fatGrams.toLong(),
            actorId = actorId,
            actorRole = actorRole
        )
    }

    suspend fun getProfile(userId: String, actorId: String, actorRole: Role) =
        profileRepository.getProfileByUserId(userId)

    data class MacroTargets(
        val calories: Int,
        val proteinGrams: Int,
        val carbGrams: Int,
        val fatGrams: Int
    )

    /**
     * Deterministic macro calculation based on goal and age range.
     * Base calories adjusted by goal deficit/surplus.
     */
    fun calculateMacros(goal: HealthGoal, ageRange: AgeRange): MacroTargets {
        val baseCal = when (ageRange) {
            AgeRange.AGE_18_25 -> 2200
            AgeRange.AGE_26_35 -> 2100
            AgeRange.AGE_36_45 -> 2000
            AgeRange.AGE_46_55 -> 1900
            AgeRange.AGE_56_65 -> 1800
            AgeRange.AGE_65_PLUS -> 1700
        }

        val calorieAdjustment = when (goal) {
            HealthGoal.LOSE_2_LB_WEEK -> -1000
            HealthGoal.LOSE_1_LB_WEEK -> -500
            HealthGoal.LOSE_HALF_LB_WEEK -> -250
            HealthGoal.MAINTAIN -> 0
            HealthGoal.GAIN_HALF_LB_WEEK -> 250
            HealthGoal.GAIN_1_LB_WEEK -> 500
        }

        val totalCalories = (baseCal + calorieAdjustment).coerceAtLeast(1200)

        // Macro split: 30% protein, 40% carbs, 30% fat
        val proteinCal = (totalCalories * 0.30).toInt()
        val carbCal = (totalCalories * 0.40).toInt()
        val fatCal = (totalCalories * 0.30).toInt()

        return MacroTargets(
            calories = totalCalories,
            proteinGrams = proteinCal / 4,  // 4 cal per gram protein
            carbGrams = carbCal / 4,        // 4 cal per gram carb
            fatGrams = fatCal / 9           // 9 cal per gram fat
        )
    }
}

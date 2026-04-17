package com.nutriops.app.integration_tests.domain.usecase.mealplan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.AgeRange
import com.nutriops.app.domain.model.DietaryPattern
import com.nutriops.app.domain.model.HealthGoal
import com.nutriops.app.domain.model.MealTime
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.mealplan.GenerateWeeklyPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class SwapMealUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var generatePlan: GenerateWeeklyPlanUseCase
    private lateinit var swapUseCase: SwapMealUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        mealPlanRepository = MealPlanRepository(database, auditManager)
        profileRepository = ProfileRepository(database, auditManager)
        val profileUseCase = ManageProfileUseCase(profileRepository)
        generatePlan = GenerateWeeklyPlanUseCase(mealPlanRepository, profileRepository)
        swapUseCase = SwapMealUseCase(mealPlanRepository)

        // Seed profile so we can generate a real plan with real swap mappings
        runBlocking {
            profileUseCase.createProfile(
                userId = "user1", ageRange = AgeRange.AGE_26_35,
                dietaryPattern = DietaryPattern.STANDARD, allergies = emptyList(),
                goal = HealthGoal.MAINTAIN,
                preferredMealTimes = listOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER),
                actorId = "user1", actorRole = Role.END_USER
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private suspend fun generatePlanAndPickFirstMeal(): Pair<String, String> {
        val planId = generatePlan.execute(
            userId = "user1", weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!
        val meal = mealPlanRepository.getMealsByPlanId(planId).first()
        return planId to meal.id
    }

    @Test
    fun `getAvailableSwaps returns persisted swap options for the owner`() = runBlocking {
        val (_, mealId) = generatePlanAndPickFirstMeal()

        val result = swapUseCase.getAvailableSwaps(mealId, "user1", Role.END_USER)

        assertThat(result.isSuccess).isTrue()
        val options = result.getOrNull()!!
        assertThat(options).isNotEmpty()
    }

    @Test
    fun `executeSwap replaces the original meal row with a new one in the real DB`() = runBlocking {
        val (planId, originalId) = generatePlanAndPickFirstMeal()
        val swaps = swapUseCase.getAvailableSwaps(originalId, "user1", Role.END_USER).getOrNull()!!
        val inTolerance = swaps.first {
            // Pick the first in-tolerance swap so performSwap accepts it
            mealPlanRepository.getSwapsForMeal(originalId).any { row ->
                row.id == it.swapId && row.isWithinTolerance == 1L
            }
        }

        val newMealId = swapUseCase.executeSwap(
            originalMealId = originalId, swapMappingId = inTolerance.swapId,
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val afterMeals = mealPlanRepository.getMealsByPlanId(planId)
        // Original row is gone; new row replaces it
        assertThat(afterMeals.map { it.id }).doesNotContain(originalId)
        assertThat(afterMeals.map { it.id }).contains(newMealId)

        val swappedMeal = mealPlanRepository.getMealsByPlanId(planId).first { it.id == newMealId }
        assertThat(swappedMeal.isSwapped).isEqualTo(1L)
        assertThat(swappedMeal.originalMealId).isEqualTo(originalId)
    }

    @Test
    fun `executeSwap denied for non-owner end user - DB remains unchanged`() = runBlocking {
        val (planId, originalId) = generatePlanAndPickFirstMeal()
        val swaps = swapUseCase.getAvailableSwaps(originalId, "user1", Role.END_USER).getOrNull()!!

        val before = mealPlanRepository.getMealsByPlanId(planId).size

        val result = swapUseCase.executeSwap(
            originalMealId = originalId,
            swapMappingId = swaps.first().swapId,
            actorId = "attacker", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Plan's meal set is unchanged
        assertThat(mealPlanRepository.getMealsByPlanId(planId).size).isEqualTo(before)
    }

    @Test
    fun `executeSwap rejects a mapping that does not belong to the target meal (IDOR guard)`() = runBlocking {
        val (_, originalId) = generatePlanAndPickFirstMeal()

        val result = swapUseCase.executeSwap(
            originalMealId = originalId,
            swapMappingId = "not-a-real-swap-id",
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }
}

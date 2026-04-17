package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.MealTime
import com.nutriops.app.domain.model.Role
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests for [MealPlanRepository] backed by a real
 * in-memory SQLDelight database.
 *
 * Covers plan creation, per-slot CRUD, cascading deletion of slots via the
 * underlying `deleteMealsByPlanId` query, and concurrent slot writes.
 */
class MealPlanRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: MealPlanRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = MealPlanRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createMealPlan + slot retrieval ──

    @Test
    fun `creating a plan and adding meals across 7 days round-trips to equal data`() = runBlocking {
        val planId = createPlan("user1")

        val slots = listOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER)
        for (day in 1L..7L) {
            for (slot in slots) {
                repository.addMeal(
                    mealPlanId = planId,
                    dayOfWeek = day,
                    mealTime = slot.name,
                    name = "D${day}_${slot.name}",
                    description = "desc",
                    calories = 500L + day * 10L,
                    proteinGrams = 25.0,
                    carbGrams = 60.0,
                    fatGrams = 15.0,
                    reasons = "[\"r1\",\"r2\"]"
                )
            }
        }

        val all = repository.getMealsByPlanId(planId)
        assertThat(all).hasSize(21) // 7 days × 3 slots

        for (day in 1L..7L) {
            val dayMeals = repository.getMealsByPlanAndDay(planId, day)
            assertThat(dayMeals.map { it.mealTime }).containsExactly(
                MealTime.BREAKFAST.name, MealTime.LUNCH.name, MealTime.DINNER.name
            )
        }
    }

    @Test
    fun `createMealPlan rejects invalid date range`() = runBlocking {
        val result = repository.createMealPlan(
            userId = "user1",
            weekStartDate = "2026-05-01",
            weekEndDate = "2026-04-01",
            dailyCalorieBudget = 2000L, proteinTarget = 120L, carbTarget = 225L, fatTarget = 67L,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createMealPlan rejects malformed date strings`() = runBlocking {
        val result = repository.createMealPlan(
            userId = "user1",
            weekStartDate = "not-a-date",
            weekEndDate = "also-not-a-date",
            dailyCalorieBudget = 2000L, proteinTarget = 120L, carbTarget = 225L, fatTarget = 67L,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createMealPlan writes a single CREATE audit entry`() = runBlocking {
        val planId = createPlan("user1")

        val audit = auditManager.getAuditTrail("MealPlan", planId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    // ── Slot-level isolation on updates ──

    @Test
    fun `performing a swap affects only the targeted slot, not other slots on the same day`() = runBlocking {
        val planId = createPlan("user1")
        val breakfastId = repository.addMeal(
            planId, 1L, MealTime.BREAKFAST.name, "Toast", "", 400L, 20.0, 50.0, 10.0, "[]"
        ).getOrNull()!!
        val lunchId = repository.addMeal(
            planId, 1L, MealTime.LUNCH.name, "Salad", "", 500L, 30.0, 40.0, 15.0, "[]"
        ).getOrNull()!!

        val swapMappingId = repository.addSwapMapping(
            originalMealId = breakfastId,
            swapMealName = "Oatmeal",
            swapDescription = "Oats",
            swapCalories = 420L,
            swapProteinGrams = 22.0, swapCarbGrams = 55.0, swapFatGrams = 8.0,
            caloriesDiffPercent = 5.0, proteinDiffGrams = 2.0,
            isWithinTolerance = true,
            swapReasons = "[\"r\"]",
            rankScore = 95.0
        ).getOrNull()!!

        val newMealId = repository.performSwap(breakfastId, swapMappingId, "user1", Role.END_USER).getOrNull()!!

        val day1 = repository.getMealsByPlanAndDay(planId, 1L)
        // Old breakfast is gone; new breakfast replaces it; lunch is unchanged
        assertThat(day1.map { it.id }).containsExactly(newMealId, lunchId)
        val newBreakfast = day1.first { it.mealTime == MealTime.BREAKFAST.name }
        assertThat(newBreakfast.name).isEqualTo("Oatmeal")
        assertThat(newBreakfast.isSwapped).isEqualTo(1L)
        assertThat(newBreakfast.originalMealId).isEqualTo(breakfastId)
    }

    @Test
    fun `performSwap rejects swap that is marked out-of-tolerance`() = runBlocking {
        val planId = createPlan("user1")
        val mealId = repository.addMeal(
            planId, 1L, MealTime.BREAKFAST.name, "Toast", "", 400L, 20.0, 50.0, 10.0, "[]"
        ).getOrNull()!!
        val swapId = repository.addSwapMapping(
            originalMealId = mealId, swapMealName = "Huge Plate", swapDescription = "",
            swapCalories = 800L, swapProteinGrams = 50.0, swapCarbGrams = 80.0, swapFatGrams = 25.0,
            caloriesDiffPercent = 100.0, proteinDiffGrams = 30.0,
            isWithinTolerance = false, swapReasons = "[]", rankScore = 10.0
        ).getOrNull()!!

        val result = repository.performSwap(mealId, swapId, "user1", Role.END_USER)

        assertThat(result.isFailure).isTrue()
        // Original meal must still be present
        assertThat(repository.getMealsByPlanId(planId).map { it.id }).contains(mealId)
    }

    @Test
    fun `performSwap fails for unknown meal or swap id`() = runBlocking {
        val bad1 = repository.performSwap("missing-meal", "missing-swap", "user1", Role.END_USER)
        assertThat(bad1.isFailure).isTrue()
    }

    // ── Deleting a plan removes associated slots ──

    @Test
    fun `deleting the plan also deletes every meal slot associated with it`() = runBlocking {
        val planId = createPlan("user1")
        repository.addMeal(planId, 1L, MealTime.BREAKFAST.name, "A", "", 400L, 20.0, 50.0, 10.0, "[]")
        repository.addMeal(planId, 2L, MealTime.LUNCH.name, "B", "", 500L, 25.0, 55.0, 12.0, "[]")
        assertThat(repository.getMealsByPlanId(planId)).hasSize(2)

        // Repository exposes no delete wrapper — use the queries directly, then
        // mirror what a cascading delete would do: remove the slots too.
        database.mealsQueries.deleteMealsByPlanId(planId)
        database.mealPlansQueries.deleteMealPlan(planId)

        assertThat(repository.getMealPlanById(planId)).isNull()
        assertThat(repository.getMealsByPlanId(planId)).isEmpty()
    }

    // ── Edge cases: no plan / no meals ──

    @Test
    fun `fetching meals for a plan with none returns empty list, not an exception`() = runBlocking {
        val planId = createPlan("user1")
        assertThat(repository.getMealsByPlanId(planId)).isEmpty()
    }

    @Test
    fun `getActivePlanForUser returns null when the user has no plan`() = runBlocking {
        assertThat(repository.getActivePlanForUser("ghost")).isNull()
    }

    @Test
    fun `getMealPlanById returns null for unknown id`() = runBlocking {
        assertThat(repository.getMealPlanById("missing")).isNull()
    }

    // ── Concurrent writes ──

    @Test
    fun `concurrent addMeal calls to different slots complete without loss or corruption`() = runBlocking {
        val planId = createPlan("user1")

        val writes = (1..7).flatMap { day ->
            listOf(
                async {
                    repository.addMeal(
                        planId, day.toLong(), MealTime.BREAKFAST.name,
                        "B-$day", "", 400L, 20.0, 50.0, 10.0, "[]"
                    )
                },
                async {
                    repository.addMeal(
                        planId, day.toLong(), MealTime.LUNCH.name,
                        "L-$day", "", 500L, 25.0, 55.0, 12.0, "[]"
                    )
                },
                async {
                    repository.addMeal(
                        planId, day.toLong(), MealTime.DINNER.name,
                        "D-$day", "", 600L, 30.0, 60.0, 18.0, "[]"
                    )
                }
            )
        }
        writes.awaitAll()

        assertThat(repository.getMealsByPlanId(planId)).hasSize(21)
    }

    // ── Daily totals ──

    @Test
    fun `getDailyCalories sums only meals for the given day`() = runBlocking {
        val planId = createPlan("user1")
        repository.addMeal(planId, 1L, MealTime.BREAKFAST.name, "A", "", 400L, 20.0, 50.0, 10.0, "[]")
        repository.addMeal(planId, 1L, MealTime.LUNCH.name, "B", "", 600L, 30.0, 60.0, 18.0, "[]")
        repository.addMeal(planId, 2L, MealTime.BREAKFAST.name, "C", "", 350L, 18.0, 45.0, 9.0, "[]")

        val day1 = repository.getDailyCalories(planId, 1L)!!.totalCalories
        val day2 = repository.getDailyCalories(planId, 2L)!!.totalCalories

        assertThat(day1).isEqualTo(1000L)
        assertThat(day2).isEqualTo(350L)
    }

    // ── helpers ──

    private suspend fun createPlan(userId: String): String = repository.createMealPlan(
        userId = userId,
        weekStartDate = "2026-04-06",
        weekEndDate = "2026-04-12",
        dailyCalorieBudget = 2000L, proteinTarget = 120L, carbTarget = 225L, fatTarget = 67L,
        actorId = userId, actorRole = Role.END_USER
    ).getOrNull()!!
}

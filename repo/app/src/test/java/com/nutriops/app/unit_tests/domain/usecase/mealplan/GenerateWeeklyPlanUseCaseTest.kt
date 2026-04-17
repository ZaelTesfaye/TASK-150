package com.nutriops.app.unit_tests.domain.usecase.mealplan

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.Profiles
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.DietaryPattern
import com.nutriops.app.domain.model.HealthGoal
import com.nutriops.app.domain.model.MealTime
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.mealplan.GenerateWeeklyPlanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GenerateWeeklyPlanUseCaseTest {

    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var useCase: GenerateWeeklyPlanUseCase

    @Before
    fun setup() {
        mealPlanRepository = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        useCase = GenerateWeeklyPlanUseCase(mealPlanRepository, profileRepository)
    }

    private fun profile(
        userId: String = "user1",
        dietaryPattern: DietaryPattern = DietaryPattern.STANDARD,
        goal: HealthGoal = HealthGoal.MAINTAIN,
        dailyCalories: Long = 2000L,
        protein: Long = 120L,
        carbs: Long = 225L,
        fat: Long = 67L
    ): Profiles = Profiles(
        id = "p-$userId",
        userId = userId,
        ageRange = "AGE_26_35",
        dietaryPattern = dietaryPattern.name,
        allergies = "[]",
        goal = goal.name,
        preferredMealTimes = "[]",
        dailyCalorieBudget = dailyCalories,
        proteinTargetGrams = protein,
        carbTargetGrams = carbs,
        fatTargetGrams = fat,
        createdAt = "2026-01-01T00:00:00",
        updatedAt = "2026-01-01T00:00:00"
    )

    @Test
    fun `execute creates meal plan and adds 3 meals per day for 7 days`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns profile()
        coEvery {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("plan1")
        coEvery { mealPlanRepository.getMealsByPlanId("plan1") } returns emptyList()

        val result = useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("plan1")

        // 3 meals/day × 7 days = 21 addMeal calls
        coVerify(exactly = 21) {
            mealPlanRepository.addMeal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `execute adds one meal per meal-time slot per day`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns profile()
        coEvery {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("plan1")
        coEvery { mealPlanRepository.getMealsByPlanId("plan1") } returns emptyList()

        val mealTimeSlot = slot<String>()
        val daySlot = slot<Long>()
        val capturedDayMealTimes = mutableListOf<Pair<Long, String>>()

        coEvery {
            mealPlanRepository.addMeal(
                mealPlanId = any(),
                dayOfWeek = capture(daySlot),
                mealTime = capture(mealTimeSlot),
                name = any(), description = any(), calories = any(),
                proteinGrams = any(), carbGrams = any(), fatGrams = any(), reasons = any()
            )
        } answers {
            capturedDayMealTimes += daySlot.captured to mealTimeSlot.captured
            Result.success("meal-${daySlot.captured}-${mealTimeSlot.captured}")
        }

        useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1",
            actorRole = Role.END_USER
        )

        // Every day 1..7 must have exactly one BREAKFAST, LUNCH and DINNER
        for (day in 1L..7L) {
            val slots = capturedDayMealTimes.filter { it.first == day }.map { it.second }.toSet()
            assertThat(slots).containsExactly(
                MealTime.BREAKFAST.name, MealTime.LUNCH.name, MealTime.DINNER.name
            )
        }
    }

    @Test
    fun `execute allocates calories with 30-40-30 split that sums to the daily budget`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns profile(dailyCalories = 2000L)
        coEvery {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("plan1")
        coEvery { mealPlanRepository.getMealsByPlanId("plan1") } returns emptyList()

        val dayCapture = slot<Long>()
        val mealTimeCapture = slot<String>()
        val caloriesCapture = slot<Long>()
        val captured = mutableListOf<Triple<Long, String, Long>>()

        coEvery {
            mealPlanRepository.addMeal(
                mealPlanId = any(),
                dayOfWeek = capture(dayCapture),
                mealTime = capture(mealTimeCapture),
                name = any(), description = any(),
                calories = capture(caloriesCapture),
                proteinGrams = any(), carbGrams = any(), fatGrams = any(), reasons = any()
            )
        } answers {
            captured += Triple(dayCapture.captured, mealTimeCapture.captured, caloriesCapture.captured)
            Result.success("meal-${dayCapture.captured}-${mealTimeCapture.captured}")
        }

        useCase.execute("user1", LocalDate.of(2026, 4, 6), "user1", Role.END_USER)

        // For a given day, breakfast=600, lunch=800, dinner=600 → total 2000
        val firstDay = captured.filter { it.first == 1L }
        val breakfast = firstDay.first { it.second == "BREAKFAST" }.third
        val lunch = firstDay.first { it.second == "LUNCH" }.third
        val dinner = firstDay.first { it.second == "DINNER" }.third

        assertThat(breakfast + lunch + dinner).isEqualTo(2000L)
        assertThat(breakfast).isEqualTo(600L)
        assertThat(lunch).isEqualTo(800L)
        assertThat(dinner).isEqualTo(600L)
    }

    @Test
    fun `execute fails with an explicit error when user profile is missing`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns null

        val result = useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("profile")
        coVerify(exactly = 0) {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `execute denies non-owner end user (BOLA protection)`() = runBlocking {
        val result = useCase.execute(
            userId = "userB",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "userA",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { profileRepository.getProfileByUserId(any()) }
    }

    @Test
    fun `execute propagates repository failure from createMealPlan`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns profile()
        coEvery {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("db unavailable"))

        val result = useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("db unavailable")
        coVerify(exactly = 0) {
            mealPlanRepository.addMeal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `execute attaches explainable reasons to every generated meal (at least 2 per meal)`() = runBlocking {
        coEvery { profileRepository.getProfileByUserId("user1") } returns profile()
        coEvery {
            mealPlanRepository.createMealPlan(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("plan1")
        coEvery { mealPlanRepository.getMealsByPlanId("plan1") } returns emptyList()

        val reasonsCapture = slot<String>()
        val capturedReasons = mutableListOf<String>()

        coEvery {
            mealPlanRepository.addMeal(
                mealPlanId = any(), dayOfWeek = any(), mealTime = any(),
                name = any(), description = any(), calories = any(),
                proteinGrams = any(), carbGrams = any(), fatGrams = any(),
                reasons = capture(reasonsCapture)
            )
        } answers {
            capturedReasons += reasonsCapture.captured
            Result.success("meal-${capturedReasons.size}")
        }

        useCase.execute("user1", LocalDate.of(2026, 4, 6), "user1", Role.END_USER)

        assertThat(capturedReasons).isNotEmpty()
        for (reasons in capturedReasons) {
            // reasons is stored as a JSON-like array string: ["r1","r2",...]
            val count = reasons.count { it == ',' } + 1
            assertThat(count).isAtLeast(2)
        }
    }
}

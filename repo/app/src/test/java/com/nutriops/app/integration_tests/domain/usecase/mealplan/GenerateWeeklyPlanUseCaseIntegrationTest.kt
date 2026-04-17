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
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GenerateWeeklyPlanUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var profileUseCase: ManageProfileUseCase
    private lateinit var useCase: GenerateWeeklyPlanUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        profileRepository = ProfileRepository(database, auditManager)
        mealPlanRepository = MealPlanRepository(database, auditManager)
        profileUseCase = ManageProfileUseCase(profileRepository)
        useCase = GenerateWeeklyPlanUseCase(mealPlanRepository, profileRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private suspend fun seedProfile(userId: String) {
        profileUseCase.createProfile(
            userId = userId, ageRange = AgeRange.AGE_26_35,
            dietaryPattern = DietaryPattern.STANDARD, allergies = emptyList(),
            goal = HealthGoal.MAINTAIN,
            preferredMealTimes = listOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER),
            actorId = userId, actorRole = Role.END_USER
        )
    }

    @Test
    fun `execute generates a weekly plan with 21 meals against a real profile row`() = runBlocking {
        seedProfile("user1")

        val result = useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
        val planId = result.getOrNull()!!

        val plan = mealPlanRepository.getMealPlanById(planId)!!
        assertThat(plan.userId).isEqualTo("user1")
        assertThat(plan.weekStartDate).isEqualTo("2026-04-06")
        assertThat(plan.status).isEqualTo("ACTIVE")

        val meals = mealPlanRepository.getMealsByPlanId(planId)
        assertThat(meals).hasSize(21) // 7 days * 3 slots
    }

    @Test
    fun `execute writes swap options linked to every generated meal`() = runBlocking {
        seedProfile("user1")

        val planId = useCase.execute(
            userId = "user1",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val meals = mealPlanRepository.getMealsByPlanId(planId)
        // Every meal has at least one swap mapping row
        for (meal in meals) {
            val swaps = mealPlanRepository.getSwapsForMeal(meal.id)
            assertThat(swaps).isNotEmpty()
        }
    }

    @Test
    fun `execute fails with missing-profile error when the user has no profile row`() = runBlocking {
        val result = useCase.execute(
            userId = "user-without-profile",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user-without-profile", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("profile")
    }

    @Test
    fun `execute denies non-owner end user (BOLA) and no plan is created`() = runBlocking {
        seedProfile("victim")

        val result = useCase.execute(
            userId = "victim",
            weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "attacker", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(mealPlanRepository.getMealPlansByUserId("victim")).isEmpty()
    }

    @Test
    fun `generated meal calories sum per day to the configured daily budget`() = runBlocking {
        seedProfile("user1")
        val planId = useCase.execute(
            userId = "user1", weekStartDate = LocalDate.of(2026, 4, 6),
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val plan = mealPlanRepository.getMealPlanById(planId)!!
        for (day in 1L..7L) {
            val dailyCal = mealPlanRepository.getDailyCalories(planId, day)!!.totalCalories
            assertThat(dailyCal).isEqualTo(plan.dailyCalorieBudget)
        }
    }
}

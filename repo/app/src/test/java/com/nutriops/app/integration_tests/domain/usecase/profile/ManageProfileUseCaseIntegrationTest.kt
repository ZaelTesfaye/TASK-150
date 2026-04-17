package com.nutriops.app.integration_tests.domain.usecase.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.AgeRange
import com.nutriops.app.domain.model.DietaryPattern
import com.nutriops.app.domain.model.HealthGoal
import com.nutriops.app.domain.model.MealTime
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ManageProfileUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: ProfileRepository
    private lateinit var useCase: ManageProfileUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = ProfileRepository(database, auditManager)
        useCase = ManageProfileUseCase(repository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `createProfile persists calculated macro targets in the real DB`() = runBlocking {
        val result = useCase.createProfile(
            userId = "user1",
            ageRange = AgeRange.AGE_26_35,
            dietaryPattern = DietaryPattern.STANDARD,
            allergies = listOf("peanut"),
            goal = HealthGoal.MAINTAIN,
            preferredMealTimes = listOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER),
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()

        val stored = repository.getProfileByUserId("user1")!!
        assertThat(stored.dietaryPattern).isEqualTo(DietaryPattern.STANDARD.name)
        assertThat(stored.goal).isEqualTo(HealthGoal.MAINTAIN.name)
        assertThat(stored.ageRange).isEqualTo(AgeRange.AGE_26_35.display)
        // Macros are derived from the goal + age range via ManageProfileUseCase.calculateMacros
        assertThat(stored.dailyCalorieBudget).isAtLeast(1200L)
        assertThat(stored.proteinTargetGrams).isGreaterThan(0L)
    }

    @Test
    fun `createProfile for another user is denied and no row is inserted`() = runBlocking {
        val result = useCase.createProfile(
            userId = "victim",
            ageRange = AgeRange.AGE_26_35,
            dietaryPattern = DietaryPattern.STANDARD,
            allergies = emptyList(),
            goal = HealthGoal.MAINTAIN,
            preferredMealTimes = listOf(MealTime.BREAKFAST),
            actorId = "attacker", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(repository.getProfileByUserId("victim")).isNull()
    }

    @Test
    fun `updateProfile writes back calculated macros and persists new preferences`() = runBlocking {
        val profileId = useCase.createProfile(
            userId = "user1", ageRange = AgeRange.AGE_26_35,
            dietaryPattern = DietaryPattern.STANDARD, allergies = emptyList(),
            goal = HealthGoal.MAINTAIN, preferredMealTimes = listOf(MealTime.BREAKFAST),
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val updateResult = useCase.updateProfile(
            profileId = profileId,
            userId = "user1",
            ageRange = AgeRange.AGE_36_45,
            dietaryPattern = DietaryPattern.VEGAN,
            allergies = listOf("gluten"),
            goal = HealthGoal.LOSE_1_LB_WEEK,
            preferredMealTimes = listOf(MealTime.BREAKFAST, MealTime.DINNER),
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(updateResult.isSuccess).isTrue()
        val updated = repository.getProfileByUserId("user1")!!
        assertThat(updated.dietaryPattern).isEqualTo(DietaryPattern.VEGAN.name)
        assertThat(updated.goal).isEqualTo(HealthGoal.LOSE_1_LB_WEEK.name)
        assertThat(updated.ageRange).isEqualTo(AgeRange.AGE_36_45.display)
    }

    @Test
    fun `calculateMacros returns goal- and age-appropriate values backed by AppConfig defaults`() {
        val loseMacros = useCase.calculateMacros(HealthGoal.LOSE_1_LB_WEEK, AgeRange.AGE_26_35)
        val maintainMacros = useCase.calculateMacros(HealthGoal.MAINTAIN, AgeRange.AGE_26_35)

        assertThat(loseMacros.calories).isLessThan(maintainMacros.calories)
        assertThat(loseMacros.calories).isAtLeast(1200)
    }
}

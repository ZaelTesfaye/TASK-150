package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ProfileRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: ProfileRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = ProfileRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `create profile and retrieve by user id returns equal data`() = runBlocking {
        val result = repository.createProfile(
            userId = "user1",
            ageRange = "26-35", dietaryPattern = "STANDARD", allergies = "[]",
            goal = "MAINTAIN", preferredMealTimes = "[]",
            dailyCalorieBudget = 2100L, proteinTargetGrams = 120L,
            carbTargetGrams = 240L, fatTargetGrams = 70L,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
        val profileId = result.getOrNull()!!

        val stored = repository.getProfileByUserId("user1")
        assertThat(stored).isNotNull()
        assertThat(stored!!.id).isEqualTo(profileId)
        assertThat(stored.ageRange).isEqualTo("26-35")
        assertThat(stored.dietaryPattern).isEqualTo("STANDARD")
        assertThat(stored.goal).isEqualTo("MAINTAIN")
        assertThat(stored.dailyCalorieBudget).isEqualTo(2100L)

        val audit = auditManager.getAuditTrail("Profile", profileId)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    @Test
    fun `attempting to create a second profile for the same user fails`() = runBlocking {
        createProfile("user1")
        val second = repository.createProfile(
            userId = "user1",
            ageRange = "36-45", dietaryPattern = "VEGAN", allergies = "[]",
            goal = "LOSE_1_LB_WEEK", preferredMealTimes = "[]",
            dailyCalorieBudget = 1800L, proteinTargetGrams = 130L,
            carbTargetGrams = 220L, fatTargetGrams = 60L,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(second.isFailure).isTrue()
        assertThat(second.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `update persists new fields and leaves non-targeted rows alone`() = runBlocking {
        val aId = createProfile("userA", goal = "MAINTAIN", calories = 2100L)
        val bId = createProfile("userB", goal = "MAINTAIN", calories = 1900L)

        repository.updateProfile(
            profileId = aId,
            ageRange = "36-45", dietaryPattern = "KETO", allergies = "[]",
            goal = "LOSE_1_LB_WEEK", preferredMealTimes = "[]",
            dailyCalorieBudget = 1700L, proteinTargetGrams = 140L,
            carbTargetGrams = 80L, fatTargetGrams = 110L,
            actorId = "userA", actorRole = Role.END_USER
        )

        val a = repository.getProfileByUserId("userA")!!
        val b = repository.getProfileByUserId("userB")!!

        assertThat(a.goal).isEqualTo("LOSE_1_LB_WEEK")
        assertThat(a.dietaryPattern).isEqualTo("KETO")
        assertThat(a.dailyCalorieBudget).isEqualTo(1700L)

        // userB's profile is untouched
        assertThat(b.id).isEqualTo(bId)
        assertThat(b.goal).isEqualTo("MAINTAIN")
        assertThat(b.dailyCalorieBudget).isEqualTo(1900L)
    }

    @Test
    fun `fetch for unknown user id returns null, not an exception`() = runBlocking {
        val result = repository.getProfileByUserId("ghost")
        assertThat(result).isNull()
    }

    @Test
    fun `update fails for unknown profile id`() = runBlocking {
        val result = repository.updateProfile(
            profileId = "missing",
            ageRange = "26-35", dietaryPattern = "STANDARD", allergies = "[]",
            goal = "MAINTAIN", preferredMealTimes = "[]",
            dailyCalorieBudget = 2000L, proteinTargetGrams = 120L,
            carbTargetGrams = 220L, fatTargetGrams = 67L,
            actorId = "u", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `insert with invalid CHECK-constrained ageRange fails at the DB layer`() = runBlocking {
        val result = repository.createProfile(
            userId = "user1",
            ageRange = "NOT_AN_AGE", dietaryPattern = "STANDARD", allergies = "[]",
            goal = "MAINTAIN", preferredMealTimes = "[]",
            dailyCalorieBudget = 2000L, proteinTargetGrams = 120L,
            carbTargetGrams = 220L, fatTargetGrams = 67L,
            actorId = "u", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        // Profile row must not have been inserted
        assertThat(repository.getProfileByUserId("user1")).isNull()
    }

    @Test
    fun `insert with invalid CHECK-constrained dietaryPattern fails`() = runBlocking {
        val result = repository.createProfile(
            userId = "user1",
            ageRange = "26-35", dietaryPattern = "CARNIVORE", allergies = "[]",
            goal = "MAINTAIN", preferredMealTimes = "[]",
            dailyCalorieBudget = 2000L, proteinTargetGrams = 120L,
            carbTargetGrams = 220L, fatTargetGrams = 67L,
            actorId = "u", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `deleting a profile removes it from subsequent lookups`() = runBlocking {
        val profileId = createProfile("user1")
        assertThat(repository.getProfileByUserId("user1")).isNotNull()

        // The repository exposes no delete wrapper — simulate the cascade-on-
        // user-delete path by calling the underlying query directly.
        database.profilesQueries.deleteProfile(profileId)

        assertThat(repository.getProfileByUserId("user1")).isNull()
    }

    // ── helpers ──

    private suspend fun createProfile(
        userId: String,
        goal: String = "MAINTAIN",
        calories: Long = 2000L
    ): String = repository.createProfile(
        userId = userId,
        ageRange = "26-35", dietaryPattern = "STANDARD", allergies = "[]",
        goal = goal, preferredMealTimes = "[]",
        dailyCalorieBudget = calories, proteinTargetGrams = 120L,
        carbTargetGrams = 220L, fatTargetGrams = 67L,
        actorId = userId, actorRole = Role.END_USER
    ).getOrNull()!!
}

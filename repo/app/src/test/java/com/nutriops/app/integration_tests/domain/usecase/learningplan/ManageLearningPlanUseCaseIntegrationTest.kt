package com.nutriops.app.integration_tests.domain.usecase.learningplan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ManageLearningPlanUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: LearningPlanRepository
    private lateinit var useCase: ManageLearningPlanUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = LearningPlanRepository(database, auditManager)
        useCase = ManageLearningPlanUseCase(repository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `createPlan persists a real plan row owned by the actor`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "Nutrition 101",
            description = "Basics", startDate = "2026-04-01",
            endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
        val planId = result.getOrNull()!!

        val stored = repository.getLearningPlanById(planId)!!
        assertThat(stored.userId).isEqualTo("user1")
        assertThat(stored.title).isEqualTo("Nutrition 101")
        assertThat(stored.status).isEqualTo(LearningPlanStatus.NOT_STARTED.name)
    }

    @Test
    fun `createPlan for another user is denied and no row is written`() = runBlocking {
        val result = useCase.createPlan(
            userId = "victim", title = "t", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01",
            frequencyPerWeek = 3,
            actorId = "attacker", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(repository.getLearningPlansByUserId("victim")).isEmpty()
    }

    @Test
    fun `transitionStatus persists the new status in the real DB for the owner`() = runBlocking {
        val planId = useCase.createPlan(
            userId = "user1", title = "t", description = "",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val result = useCase.transitionStatus(
            planId, LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        val stored = repository.getLearningPlanById(planId)!!
        assertThat(stored.status).isEqualTo(LearningPlanStatus.IN_PROGRESS.name)
    }

    @Test
    fun `duplicateForEditing creates a new plan row linked to the parent`() = runBlocking {
        val parentId = useCase.createPlan(
            userId = "user1", title = "Parent", description = "",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!
        // Walk to COMPLETED
        useCase.transitionStatus(parentId, LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)
        useCase.transitionStatus(parentId, LearningPlanStatus.COMPLETED, "user1", Role.END_USER)

        val duplicated = useCase.duplicateForEditing(parentId, "user1", Role.END_USER).getOrNull()!!
        val newPlan = repository.getLearningPlanById(duplicated)!!
        assertThat(newPlan.parentPlanId).isEqualTo(parentId)
        assertThat(newPlan.status).isEqualTo(LearningPlanStatus.NOT_STARTED.name)
        assertThat(newPlan.title).contains("(Copy)")
    }

    @Test
    fun `non-owner end user cannot read another users plans - returns empty list`() = runBlocking {
        useCase.createPlan(
            userId = "userA", title = "private", description = "",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "userA", actorRole = Role.END_USER
        )

        val result = useCase.getPlans("userA", "userB", Role.END_USER)
        assertThat(result).isEmpty()
    }
}

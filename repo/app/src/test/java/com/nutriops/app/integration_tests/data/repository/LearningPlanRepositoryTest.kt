package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests for [LearningPlanRepository] backed by a real
 * in-memory SQLDelight database. Verifies CRUD behavior, state-machine
 * transitions, duplicate-before-edit and transactional writes with audit.
 */
class LearningPlanRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: LearningPlanRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = LearningPlanRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createLearningPlan ──

    @Test
    fun `create inserts a plan and retrieving it returns equal data`() = runBlocking {
        val result = repository.createLearningPlan(
            userId = "user1", title = "My Plan", description = "desc",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        val planId = result.getOrNull()!!

        val stored = repository.getLearningPlanById(planId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.userId).isEqualTo("user1")
        assertThat(stored.title).isEqualTo("My Plan")
        assertThat(stored.frequencyPerWeek).isEqualTo(3L)
        assertThat(stored.status).isEqualTo(LearningPlanStatus.NOT_STARTED.name)
        assertThat(stored.parentPlanId).isNull()

        val audit = auditManager.getAuditTrail("LearningPlan", planId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    @Test
    fun `create rejects invalid date range`() = runBlocking {
        val result = repository.createLearningPlan(
            userId = "user1", title = "p", description = "",
            startDate = "2026-05-01", endDate = "2026-04-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create rejects frequency outside 1-7 range`() = runBlocking {
        val tooLow = repository.createLearningPlan(
            userId = "user1", title = "p", description = "",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 0,
            actorId = "user1", actorRole = Role.END_USER
        )
        val tooHigh = repository.createLearningPlan(
            userId = "user1", title = "p", description = "",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 8,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(tooLow.isFailure).isTrue()
        assertThat(tooHigh.isFailure).isTrue()
    }

    // ── transitionStatus (update persists) ──

    @Test
    fun `transitionStatus persists new status and writes audit`() = runBlocking {
        val planId = createPlan()

        val result = repository.transitionStatus(planId, LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)

        assertThat(result.isSuccess).isTrue()
        val updated = repository.getLearningPlanById(planId)!!
        assertThat(updated.status).isEqualTo(LearningPlanStatus.IN_PROGRESS.name)

        val audit = auditManager.getAuditTrail("LearningPlan", planId)
            .filter { it.action == AuditAction.STATUS_CHANGE.name }
        assertThat(audit).hasSize(1)
        assertThat(audit.first().newState).contains("IN_PROGRESS")
    }

    @Test
    fun `transitionStatus rejects invalid transition and leaves status unchanged`() = runBlocking {
        val planId = createPlan()

        // NOT_STARTED → COMPLETED is not allowed directly
        val result = repository.transitionStatus(planId, LearningPlanStatus.COMPLETED, "user1", Role.END_USER)

        assertThat(result.isFailure).isTrue()
        val stored = repository.getLearningPlanById(planId)!!
        assertThat(stored.status).isEqualTo(LearningPlanStatus.NOT_STARTED.name)
    }

    @Test
    fun `transitionStatus fails for unknown plan id`() = runBlocking {
        val result = repository.transitionStatus(
            "missing", LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── duplicatePlan ──

    @Test
    fun `duplicatePlan requires source plan to be COMPLETED`() = runBlocking {
        val planId = createPlan() // NOT_STARTED

        val result = repository.duplicatePlan(planId, "user1", Role.END_USER)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `duplicatePlan creates a new plan linked to parent with NOT_STARTED status`() = runBlocking {
        val planId = createPlan()
        // Walk status machine to COMPLETED: NOT_STARTED → IN_PROGRESS → COMPLETED
        repository.transitionStatus(planId, LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)
        repository.transitionStatus(planId, LearningPlanStatus.COMPLETED, "user1", Role.END_USER)

        val dupeResult = repository.duplicatePlan(planId, "user1", Role.END_USER)
        assertThat(dupeResult.isSuccess).isTrue()

        val newId = dupeResult.getOrNull()!!
        val newPlan = repository.getLearningPlanById(newId)!!
        assertThat(newPlan.status).isEqualTo(LearningPlanStatus.NOT_STARTED.name)
        assertThat(newPlan.parentPlanId).isEqualTo(planId)
        assertThat(newPlan.title).contains("(Copy)")

        val audit = auditManager.getAuditTrail("LearningPlan", newId)
        assertThat(audit.map { it.action }).contains(AuditAction.DUPLICATE_PLAN.name)
    }

    // ── Queries ──

    @Test
    fun `getLearningPlansByUserId returns empty list for user with no plans`() = runBlocking {
        val plans = repository.getLearningPlansByUserId("ghost")
        assertThat(plans).isEmpty()
    }

    @Test
    fun `getLearningPlansByUserId returns only that users plans`() = runBlocking {
        createPlan(userId = "userA", title = "A1")
        createPlan(userId = "userA", title = "A2")
        createPlan(userId = "userB", title = "B1")

        val a = repository.getLearningPlansByUserId("userA")
        val b = repository.getLearningPlansByUserId("userB")

        assertThat(a).hasSize(2)
        assertThat(b).hasSize(1)
        assertThat(a.all { it.userId == "userA" }).isTrue()
        assertThat(b.first().userId).isEqualTo("userB")
    }

    @Test
    fun `getLearningPlansByStatus filters by state`() = runBlocking {
        val p1 = createPlan(userId = "user1", title = "P1")
        createPlan(userId = "user1", title = "P2")
        repository.transitionStatus(p1, LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)

        val inProgress = repository.getLearningPlansByStatus("user1", LearningPlanStatus.IN_PROGRESS)
        val notStarted = repository.getLearningPlansByStatus("user1", LearningPlanStatus.NOT_STARTED)

        assertThat(inProgress).hasSize(1)
        assertThat(inProgress.first().id).isEqualTo(p1)
        assertThat(notStarted).hasSize(1)
    }

    // ── Transactional integrity under concurrent writes ──

    @Test
    fun `concurrent plan creation preserves row count (transactional integrity)`() = runBlocking {
        val creates = (1..20).map { idx ->
            async { createPlan(userId = "user1", title = "P$idx") }
        }
        creates.awaitAll()

        val plans = repository.getLearningPlansByUserId("user1")
        assertThat(plans).hasSize(20)
        // Each plan must have a corresponding audit entry
        for (plan in plans) {
            val audit = auditManager.getAuditTrail("LearningPlan", plan.id)
            assertThat(audit).hasSize(1)
        }
    }

    // ── helpers ──

    private suspend fun createPlan(
        userId: String = "user1",
        title: String = "My Plan"
    ): String = repository.createLearningPlan(
        userId = userId, title = title, description = "desc",
        startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
        actorId = userId, actorRole = Role.END_USER
    ).getOrNull()!!
}

package com.nutriops.app.unit_tests.domain.usecase.learningplan

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.LearningPlans
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ManageLearningPlanUseCaseTest {

    private lateinit var repository: LearningPlanRepository
    private lateinit var useCase: ManageLearningPlanUseCase

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        useCase = ManageLearningPlanUseCase(repository)
    }

    private fun plan(
        id: String = "plan1",
        userId: String = "user1",
        status: String = LearningPlanStatus.NOT_STARTED.name
    ): LearningPlans = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.userId } returns userId
        every { this@mockk.status } returns status
    }

    // ── createPlan: happy path ──

    @Test
    fun `createPlan delegates to repository on success for owner`() = runBlocking {
        coEvery {
            repository.createLearningPlan(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("newPlan")

        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "desc",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("newPlan")
        coVerify {
            repository.createLearningPlan("user1", "Plan", "desc",
                "2026-04-01", "2026-05-01", 3L, "user1", Role.END_USER)
        }
    }

    // ── createPlan: validation ──

    @Test
    fun `createPlan rejects blank title without touching the repository`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "   ", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) {
            repository.createLearningPlan(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createPlan rejects frequency below 1`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 0,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Frequency")
    }

    @Test
    fun `createPlan rejects frequency above 7`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 8,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createPlan rejects malformed date string`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "d",
            startDate = "not-a-date", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Invalid date format")
    }

    @Test
    fun `createPlan rejects end date earlier than start date`() = runBlocking {
        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "d",
            startDate = "2026-05-01", endDate = "2026-04-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("End date")
    }

    @Test
    fun `createPlan propagates repository failure result`() = runBlocking {
        coEvery {
            repository.createLearningPlan(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("db down"))

        val result = useCase.createPlan(
            userId = "user1", title = "Plan", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("db down")
    }

    // ── createPlan: RBAC ──

    @Test
    fun `createPlan denies end user creating plan for another user (BOLA)`() = runBlocking {
        val result = useCase.createPlan(
            userId = "userB", title = "Plan", description = "d",
            startDate = "2026-04-01", endDate = "2026-05-01", frequencyPerWeek = 3,
            actorId = "userA", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    // ── transitionStatus ──

    @Test
    fun `transitionStatus succeeds for plan owner`() = runBlocking {
        coEvery { repository.getLearningPlanById("plan1") } returns plan(userId = "user1")
        coEvery {
            repository.transitionStatus("plan1", LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)
        } returns Result.success(Unit)

        val result = useCase.transitionStatus("plan1", LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `transitionStatus denies non-owner end user (BOLA)`() = runBlocking {
        coEvery { repository.getLearningPlanById("plan1") } returns plan(userId = "userB")

        val result = useCase.transitionStatus(
            "plan1", LearningPlanStatus.IN_PROGRESS, "userA", Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) {
            repository.transitionStatus(any(), any(), any(), any())
        }
    }

    @Test
    fun `transitionStatus fails for unknown plan id`() = runBlocking {
        coEvery { repository.getLearningPlanById("missing") } returns null

        val result = useCase.transitionStatus(
            "missing", LearningPlanStatus.IN_PROGRESS, "user1", Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── duplicateForEditing ──

    @Test
    fun `duplicateForEditing succeeds for owner`() = runBlocking {
        coEvery { repository.getLearningPlanById("plan1") } returns
            plan(userId = "user1", status = LearningPlanStatus.COMPLETED.name)
        coEvery {
            repository.duplicatePlan("plan1", "user1", Role.END_USER)
        } returns Result.success("newPlan")

        val result = useCase.duplicateForEditing("plan1", "user1", Role.END_USER)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("newPlan")
    }

    @Test
    fun `duplicateForEditing denies non-owner end user`() = runBlocking {
        coEvery { repository.getLearningPlanById("plan1") } returns
            plan(userId = "userB", status = LearningPlanStatus.COMPLETED.name)

        val result = useCase.duplicateForEditing("plan1", "userA", Role.END_USER)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) {
            repository.duplicatePlan(any(), any(), any())
        }
    }

    @Test
    fun `duplicateForEditing fails for unknown plan id`() = runBlocking {
        coEvery { repository.getLearningPlanById("missing") } returns null

        val result = useCase.duplicateForEditing("missing", "user1", Role.END_USER)

        assertThat(result.isFailure).isTrue()
    }

    // ── getPlans / getPlanById ──

    @Test
    fun `getPlans returns repository result for owner`() = runBlocking {
        val list = listOf(plan(id = "p1", userId = "user1"))
        coEvery { repository.getLearningPlansByUserId("user1") } returns list

        val result = useCase.getPlans("user1", "user1", Role.END_USER)

        assertThat(result).isEqualTo(list)
    }

    @Test
    fun `getPlans returns empty list when end user queries another user`() = runBlocking {
        val result = useCase.getPlans("userB", "userA", Role.END_USER)
        assertThat(result).isEmpty()
    }

    @Test
    fun `getPlanById returns plan for owner`() = runBlocking {
        val p = plan(id = "p1", userId = "user1")
        coEvery { repository.getLearningPlanById("p1") } returns p

        val result = useCase.getPlanById("p1", "user1", Role.END_USER)

        assertThat(result).isSameInstanceAs(p)
    }

    @Test
    fun `getPlanById returns null for end user querying another users plan`() = runBlocking {
        coEvery { repository.getLearningPlanById("p1") } returns plan(id = "p1", userId = "userB")

        val result = useCase.getPlanById("p1", "userA", Role.END_USER)

        assertThat(result).isNull()
    }

    @Test
    fun `getPlanById returns null when plan is missing`() = runBlocking {
        coEvery { repository.getLearningPlanById("missing") } returns null
        val result = useCase.getPlanById("missing", "user1", Role.END_USER)
        assertThat(result).isNull()
    }

    // ── getAllowedTransitions ──

    @Test
    fun `getAllowedTransitions mirrors the domain state machine`() {
        for (status in LearningPlanStatus.entries) {
            assertThat(useCase.getAllowedTransitions(status)).isEqualTo(status.allowedTransitions())
        }
    }
}

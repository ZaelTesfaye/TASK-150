package com.nutriops.app.integration_tests.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.repository.*
import com.nutriops.app.domain.model.*
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Integration tests verifying RBAC enforcement across viewmodel/usecase paths per role.
 * Cross-role and cross-user access must be denied.
 */
class AuthorizationIntegrationTest {

    private lateinit var manageUsersUseCase: ManageUsersUseCase
    private lateinit var manageConfigUseCase: ManageConfigUseCase
    private lateinit var manageTicketUseCase: ManageTicketUseCase
    private lateinit var manageLearningPlanUseCase: ManageLearningPlanUseCase
    private lateinit var swapMealUseCase: SwapMealUseCase
    private lateinit var manageMessagingUseCase: ManageMessagingUseCase

    private val userRepository: UserRepository = mockk(relaxed = true)
    private val configRepository: ConfigRepository = mockk(relaxed = true)
    private val rolloutRepository: RolloutRepository = mockk(relaxed = true)
    private val ticketRepository: TicketRepository = mockk(relaxed = true)
    private val messageRepository: MessageRepository = mockk(relaxed = true)
    private val learningPlanRepository: LearningPlanRepository = mockk(relaxed = true)
    private val mealPlanRepository: MealPlanRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        manageUsersUseCase = ManageUsersUseCase(userRepository)
        manageConfigUseCase = ManageConfigUseCase(configRepository, rolloutRepository)
        manageTicketUseCase = ManageTicketUseCase(ticketRepository, messageRepository)
        manageLearningPlanUseCase = ManageLearningPlanUseCase(learningPlanRepository)
        swapMealUseCase = SwapMealUseCase(mealPlanRepository)
        manageMessagingUseCase = ManageMessagingUseCase(messageRepository)
    }

    // ── Admin-only operations denied for AGENT ──

    @Test
    fun `agent cannot manage users`() = runBlocking {
        val result = manageUsersUseCase.createUser("test", "password123", Role.END_USER, "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `agent cannot view all users`() = runBlocking {
        val result = manageUsersUseCase.getAllUsers(Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `agent cannot manage config`() = runBlocking {
        val result = manageConfigUseCase.createConfig("key", "val", "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `agent cannot manage rollouts`() = runBlocking {
        val result = manageConfigUseCase.startCanaryRollout("v1", 10, "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    // ── Admin-only operations denied for END_USER ──

    @Test
    fun `end user cannot manage users`() = runBlocking {
        val result = manageUsersUseCase.createUser("test", "password123", Role.END_USER, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot manage config`() = runBlocking {
        val result = manageConfigUseCase.createConfig("key", "val", "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot view all configs`() = runBlocking {
        val result = manageConfigUseCase.getAllConfigs(Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    // ── Agent operations denied for END_USER ──

    @Test
    fun `end user cannot manage tickets`() = runBlocking {
        val result = manageTicketUseCase.transitionStatus("t1", TicketStatus.ASSIGNED, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot assign tickets`() = runBlocking {
        val result = manageTicketUseCase.assignToAgent("t1", "agent1", "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot suggest compensation`() = runBlocking {
        val result = manageTicketUseCase.suggestCompensation("t1", 5.0, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot approve compensation`() = runBlocking {
        val result = manageTicketUseCase.approveCompensation("t1", 5.0, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot view open tickets list`() = runBlocking {
        val result = manageTicketUseCase.getOpenTickets(Role.END_USER)
        assertThat(result).isEmpty()
    }

    // ── Cross-user access denied ──

    @Test
    fun `end user cannot create learning plan for another user`() = runBlocking {
        val result = manageLearningPlanUseCase.createPlan(
            userId = "otherUser",
            title = "Test Plan",
            description = "desc",
            startDate = "2026-01-01",
            endDate = "2026-06-01",
            frequencyPerWeek = 3,
            actorId = "user1",
            actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot view another users tickets`() = runBlocking {
        val result = manageTicketUseCase.getTicketsByUser("otherUser", "user1", Role.END_USER)
        assertThat(result).isEmpty()
    }

    // ── Positive: Admin can do admin things ──

    @Test
    fun `admin can manage users`() = runBlocking {
        coEvery { userRepository.createUser(any(), any(), any(), any(), any()) } returns Result.success("id1")
        val result = manageUsersUseCase.createUser("test", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
    }

    // ── Positive: End user can create own ticket ──

    @Test
    fun `end user can create own ticket`() = runBlocking {
        coEvery { ticketRepository.createTicket(any(), any(), any(), any(), any(), any(), any()) } returns Result.success("t1")
        val result = manageTicketUseCase.createTicket(
            userId = "user1",
            ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM,
            subject = "Test",
            description = "desc",
            actorId = "user1",
            actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
    }

    // ── Cross-user object-level authorization (BOLA/IDOR) ──

    @Test
    fun `end user cannot transition another users learning plan`() = runBlocking {
        val otherUserPlan = mockk<com.nutriops.app.data.local.LearningPlans> {
            io.mockk.every { userId } returns "userB"
            io.mockk.every { status } returns LearningPlanStatus.NOT_STARTED.name
        }
        coEvery { learningPlanRepository.getLearningPlanById("plan-B") } returns otherUserPlan

        val result = manageLearningPlanUseCase.transitionStatus(
            planId = "plan-B",
            newStatus = LearningPlanStatus.IN_PROGRESS,
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot duplicate another users learning plan`() = runBlocking {
        val otherUserPlan = mockk<com.nutriops.app.data.local.LearningPlans> {
            io.mockk.every { userId } returns "userB"
            io.mockk.every { status } returns LearningPlanStatus.COMPLETED.name
        }
        coEvery { learningPlanRepository.getLearningPlanById("plan-B") } returns otherUserPlan

        val result = manageLearningPlanUseCase.duplicateForEditing(
            planId = "plan-B",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot upload evidence to another users ticket`() = runBlocking {
        val otherUserTicket = mockk<com.nutriops.app.data.local.Tickets> {
            io.mockk.every { userId } returns "userB"
        }
        coEvery { ticketRepository.getTicketById("ticket-B") } returns otherUserTicket

        val result = manageTicketUseCase.addEvidence(
            ticketId = "ticket-B",
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "planted evidence",
            uploadedBy = "userA",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot read another users messages`() = runBlocking {
        val result = manageMessagingUseCase.getMessages(
            userId = "userB",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot mark another users message as read`() = runBlocking {
        val otherUserMessage = mockk<com.nutriops.app.data.local.Messages> {
            io.mockk.every { userId } returns "userB"
        }
        coEvery { messageRepository.getMessageById("msg-B") } returns otherUserMessage

        manageMessagingUseCase.markAsRead(
            messageId = "msg-B",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        // Verify the repository markAsRead was never called
        io.mockk.coVerify(exactly = 0) { messageRepository.markAsRead("msg-B") }
    }

    @Test
    fun `end user cannot mark all messages as read for another user`() = runBlocking {
        manageMessagingUseCase.markAllAsRead(
            userId = "userB",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        io.mockk.coVerify(exactly = 0) { messageRepository.markAllAsRead("userB") }
    }

    // ── Cross-user todo authorization ──

    @Test
    fun `end user cannot read another users learning plans`() = runBlocking {
        val result = manageLearningPlanUseCase.getPlans(
            userId = "userB",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot read another users learning plan by ID`() = runBlocking {
        val otherPlan = mockk<com.nutriops.app.data.local.LearningPlans> {
            io.mockk.every { userId } returns "userB"
        }
        coEvery { learningPlanRepository.getLearningPlanById("plan-B") } returns otherPlan

        val result = manageLearningPlanUseCase.getPlanById(
            planId = "plan-B",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result).isNull()
    }

    @Test
    fun `end user cannot get another users todos`() = runBlocking {
        val result = manageMessagingUseCase.getTodos(
            userId = "userB",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot get another users incomplete todos`() = runBlocking {
        val result = manageMessagingUseCase.getIncompleteTodos(
            userId = "userB",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot create todo for another user`() = runBlocking {
        val result = manageMessagingUseCase.createTodo(
            userId = "userB",
            title = "Planted todo",
            description = "desc",
            dueDate = null,
            relatedEntityType = null,
            relatedEntityId = null,
            actorId = "userA",
            actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `end user cannot complete another users todo`() = runBlocking {
        val otherTodo = mockk<com.nutriops.app.data.local.Todos> {
            io.mockk.every { userId } returns "userB"
        }
        coEvery { messageRepository.getTodoById("todo-B") } returns otherTodo

        manageMessagingUseCase.completeTodo(
            todoId = "todo-B",
            actorId = "userA",
            actorRole = Role.END_USER
        )
        io.mockk.coVerify(exactly = 0) { messageRepository.completeTodo("todo-B") }
    }
}

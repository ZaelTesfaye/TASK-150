package com.nutriops.app.integration_tests.domain.usecase

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.*
import com.nutriops.app.domain.model.*
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.PasswordHasher
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Integration tests verifying RBAC enforcement and cross-user (BOLA/IDOR)
 * authorization across the use-case layer. Repositories, database, audit
 * manager and the encryption manager are all real, backed by an in-memory
 * SQLDelight database and AES-256-GCM via [JvmEncryptionManager]. No
 * production-boundary mocks remain -- every authorization decision runs
 * through the same code path and persistence layer the app uses at runtime.
 */
class AuthorizationIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager

    private lateinit var userRepository: UserRepository
    private lateinit var configRepository: ConfigRepository
    private lateinit var rolloutRepository: RolloutRepository
    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var learningPlanRepository: LearningPlanRepository
    private lateinit var mealPlanRepository: MealPlanRepository

    private lateinit var manageUsersUseCase: ManageUsersUseCase
    private lateinit var manageConfigUseCase: ManageConfigUseCase
    private lateinit var manageTicketUseCase: ManageTicketUseCase
    private lateinit var manageLearningPlanUseCase: ManageLearningPlanUseCase
    private lateinit var swapMealUseCase: SwapMealUseCase
    private lateinit var manageMessagingUseCase: ManageMessagingUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)

        // Real AES-256-GCM cipher with an in-memory key -- no mock on the
        // encryption boundary. Encrypted columns round-trip through the same
        // cipher the production EncryptionManager would use against the
        // Android Keystore.
        encryptionManager = JvmEncryptionManager()

        userRepository = UserRepository(database, auditManager)
        configRepository = ConfigRepository(database, auditManager)
        rolloutRepository = RolloutRepository(database, auditManager)
        ticketRepository = TicketRepository(database, auditManager, encryptionManager)
        messageRepository = MessageRepository(database)
        learningPlanRepository = LearningPlanRepository(database, auditManager)
        mealPlanRepository = MealPlanRepository(database, auditManager)

        manageUsersUseCase = ManageUsersUseCase(userRepository)
        manageConfigUseCase = ManageConfigUseCase(configRepository, rolloutRepository)
        manageTicketUseCase = ManageTicketUseCase(ticketRepository, messageRepository)
        manageLearningPlanUseCase = ManageLearningPlanUseCase(learningPlanRepository)
        swapMealUseCase = SwapMealUseCase(mealPlanRepository)
        manageMessagingUseCase = ManageMessagingUseCase(messageRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Test fixtures ──

    private fun insertUser(username: String, role: Role): String {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        database.usersQueries.insertUser(
            id = id, username = username,
            passwordHash = PasswordHasher.hash("password123"),
            role = role.name, isActive = 1, isLocked = 0,
            failedLoginAttempts = 0, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )
        return id
    }

    // ── Admin-only operations denied for AGENT ──

    @Test
    fun `agent cannot manage users`() = runBlocking {
        val result = manageUsersUseCase.createUser(
            "test", "password123", Role.END_USER, "agent1", Role.AGENT
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Verify no user was actually created in the DB
        assertThat(database.usersQueries.getUserByUsername("test").executeAsOneOrNull()).isNull()
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
        assertThat(database.configsQueries.getAllConfigs().executeAsList()).isEmpty()
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
        val result = manageUsersUseCase.createUser(
            "test", "password123", Role.END_USER, "user1", Role.END_USER
        )
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

    // ── Agent-and-admin operations denied for END_USER ──

    @Test
    fun `end user cannot transition ticket status`() = runBlocking {
        val ticketId = ticketRepository.createTicket(
            userId = "user1", ticketType = TicketType.DELAY, priority = TicketPriority.MEDIUM,
            subject = "s", description = "d", actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val result = manageTicketUseCase.transitionStatus(
            ticketId, TicketStatus.ASSIGNED, "user1", Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Status must remain OPEN
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo("OPEN")
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

    // ── Positive: Admin can manage users ──

    @Test
    fun `admin can manage users and audit trail records the action`() = runBlocking {
        val adminId = insertUser("root", Role.ADMINISTRATOR)

        val result = manageUsersUseCase.createUser(
            "alice", "password123", Role.END_USER, adminId, Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val createdId = result.getOrNull()!!
        val stored = database.usersQueries.getUserByUsername("alice").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.id).isEqualTo(createdId)
        assertThat(stored.role).isEqualTo(Role.END_USER.name)

        val audit = auditManager.getAuditTrail("User", createdId)
        assertThat(audit).isNotEmpty()
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
        assertThat(audit.first().actorId).isEqualTo(adminId)
    }

    @Test
    fun `end user can create own ticket and it is persisted`() = runBlocking {
        val result = manageTicketUseCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "Test",
            description = "desc", actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        val ticketId = result.getOrNull()!!
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOneOrNull()
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.userId).isEqualTo("user1")
    }

    // ── Cross-user object-level authorization (BOLA/IDOR) ──

    @Test
    fun `end user cannot create learning plan for another user`() = runBlocking {
        val result = manageLearningPlanUseCase.createPlan(
            userId = "otherUser", title = "Plan", description = "desc",
            startDate = "2026-01-01", endDate = "2026-06-01", frequencyPerWeek = 3,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(database.learningPlansQueries.getLearningPlansByUserId("otherUser").executeAsList()).isEmpty()
    }

    @Test
    fun `end user cannot view another users tickets`() = runBlocking {
        ticketRepository.createTicket(
            userId = "userB", ticketType = TicketType.DELAY, priority = TicketPriority.MEDIUM,
            subject = "private", description = "d", actorId = "userB", actorRole = Role.END_USER
        )

        val result = manageTicketUseCase.getTicketsByUser("userB", "userA", Role.END_USER)

        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot upload evidence to another users ticket`() = runBlocking {
        val ownerTicketId = ticketRepository.createTicket(
            userId = "userB", ticketType = TicketType.DELAY, priority = TicketPriority.MEDIUM,
            subject = "s", description = "d", actorId = "userB", actorRole = Role.END_USER
        ).getOrNull()!!

        val result = manageTicketUseCase.addEvidence(
            ticketId = ownerTicketId, evidenceType = EvidenceType.TEXT,
            contentUri = null, textContent = "planted",
            uploadedBy = "userA", fileSizeBytes = null, actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(database.evidenceItemsQueries.getEvidenceByTicketId(ownerTicketId).executeAsList()).isEmpty()
    }

    @Test
    fun `end user cannot read another users messages`() = runBlocking {
        messageRepository.sendMessage(
            userId = "userB", templateId = null, title = "secret",
            body = "body", messageType = MessageType.NOTIFICATION.name,
            triggerEvent = TriggerEvent.TICKET_UPDATED.key
        )

        val result = manageMessagingUseCase.getMessages("userB", "userA", Role.END_USER)

        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot mark another users message as read`() = runBlocking {
        val messageId = messageRepository.sendMessage(
            userId = "userB", templateId = null, title = "t",
            body = "b", messageType = MessageType.NOTIFICATION.name,
            triggerEvent = TriggerEvent.TICKET_UPDATED.key
        ).getOrNull()!!

        manageMessagingUseCase.markAsRead(messageId, "userA", Role.END_USER)

        val storedMessage = database.messagesQueries.getMessageById(messageId).executeAsOne()
        assertThat(storedMessage.isRead).isEqualTo(0L)
    }

    @Test
    fun `end user cannot mark all of another users messages as read`() = runBlocking {
        messageRepository.sendMessage(
            userId = "userB", templateId = null, title = "t", body = "b",
            messageType = MessageType.NOTIFICATION.name, triggerEvent = TriggerEvent.TICKET_UPDATED.key
        )

        manageMessagingUseCase.markAllAsRead("userB", "userA", Role.END_USER)

        val unread = database.messagesQueries.getUnreadMessagesByUserId("userB").executeAsList()
        assertThat(unread).isNotEmpty()
    }

    // ── Cross-user todo authorization ──

    @Test
    fun `end user cannot read another users learning plans`() = runBlocking {
        val result = manageLearningPlanUseCase.getPlans("userB", "userA", Role.END_USER)
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot get another users todos`() = runBlocking {
        val result = manageMessagingUseCase.getTodos("userB", "userA", Role.END_USER)
        assertThat(result).isEmpty()
    }

    @Test
    fun `end user cannot create todo for another user`() = runBlocking {
        val result = manageMessagingUseCase.createTodo(
            userId = "userB", title = "Planted todo", description = "desc",
            dueDate = null, relatedEntityType = null, relatedEntityId = null,
            actorId = "userA", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(database.messagesQueries.getTodosByUserId("userB").executeAsList()).isEmpty()
    }

    @Test
    fun `end user cannot complete another users todo`() = runBlocking {
        val todoId = messageRepository.createTodo(
            userId = "userB", title = "t", description = "d",
            dueDate = null, relatedEntityType = null, relatedEntityId = null
        ).getOrNull()!!

        manageMessagingUseCase.completeTodo(todoId, "userA", Role.END_USER)

        val stored = database.messagesQueries.getTodoById(todoId).executeAsOne()
        assertThat(stored.isCompleted).isEqualTo(0L)
    }
}

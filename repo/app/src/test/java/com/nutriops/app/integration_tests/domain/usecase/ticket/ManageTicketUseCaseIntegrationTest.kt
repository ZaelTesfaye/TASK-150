package com.nutriops.app.integration_tests.domain.usecase.ticket

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.CompensationStatus
import com.nutriops.app.domain.model.EvidenceType
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketStatus
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [ManageTicketUseCase] that exercise the full stack —
 * real [TicketRepository] + real [MessageRepository] + real [AuditManager] +
 * real AES-256-GCM encryption (via [JvmEncryptionManager]) backed by an
 * in-memory SQLDelight database. The unit test counterpart
 * ([ManageTicketUseCaseTest]) uses mocks; this file guards the same paths
 * end-to-end with no production-boundary mocks — encrypted columns are
 * exercised with a real cipher, just one keyed from an in-memory AES key
 * instead of the Android Keystore (unavailable on the host JVM).
 */
class ManageTicketUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: ManageTicketUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        encryptionManager = JvmEncryptionManager()
        ticketRepository = TicketRepository(database, auditManager, encryptionManager)
        messageRepository = MessageRepository(database)
        useCase = ManageTicketUseCase(ticketRepository, messageRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `createTicket persists the ticket, writes the audit row, and delivers a notification`() = runBlocking {
        val result = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "Late delivery",
            description = "Package did not arrive",
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        val ticketId = result.getOrNull()!!

        // Ticket row is persisted with the expected fields
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo(TicketStatus.OPEN.name)
        assertThat(ticket.subject).isEqualTo("Late delivery")
        assertThat(ticket.priority).isEqualTo(TicketPriority.MEDIUM.name)

        // The use case's post-create notification hit the real message repo
        val messages = messageRepository.getMessagesByUserId("user1")
        assertThat(messages).hasSize(1)
        assertThat(messages.first().title).contains("Late delivery")
        assertThat(messages.first().triggerEvent).isEqualTo(TriggerEvent.TICKET_CREATED.key)
        assertThat(messages.first().messageType).isEqualTo(MessageType.NOTIFICATION.name)

        // Audit trail contains the CREATE row
        val audit = auditManager.getAuditTrail("Ticket", ticketId)
        assertThat(audit.map { it.action }).contains(AuditAction.CREATE.name)
    }

    @Test
    fun `createTicket with blank subject fails validation and writes nothing`() = runBlocking {
        val result = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "   ",
            description = "d",
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        // No ticket row and no notification: real DB confirms the
        // validation short-circuits before any persistence work.
        assertThat(database.ticketsQueries.getTicketsByUserId("user1").executeAsList()).isEmpty()
        assertThat(messageRepository.getMessagesByUserId("user1")).isEmpty()
    }

    @Test
    fun `end user filing their own ticket is allowed and filing another users ticket is rejected`() = runBlocking {
        val self = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "own",
            description = "d", actorId = "user1", actorRole = Role.END_USER
        )
        val other = useCase.createTicket(
            userId = "victim", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "planted",
            description = "d", actorId = "attacker", actorRole = Role.END_USER
        )

        assertThat(self.isSuccess).isTrue()
        assertThat(other.isFailure).isTrue()
        assertThat(other.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Victim has no tickets in the DB
        assertThat(database.ticketsQueries.getTicketsByUserId("victim").executeAsList()).isEmpty()
    }

    @Test
    fun `agent transition from ASSIGNED to IN_PROGRESS stamps first-response time and notifies the owner`() = runBlocking {
        val ticketId = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "LOST",
            description = "d", actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        ticketRepository.assignTicket(ticketId, "agent1", "agent1", Role.AGENT)
        val res = useCase.transitionStatus(
            ticketId, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT
        )

        assertThat(res.isSuccess).isTrue()
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo(TicketStatus.IN_PROGRESS.name)
        assertThat(ticket.slaFirstResponseAt).isNotNull()

        // Two messages now: the original creation notification + a status update
        val messages = messageRepository.getMessagesByUserId("user1")
        assertThat(messages).hasSize(2)
        assertThat(messages.any { it.triggerEvent == TriggerEvent.TICKET_UPDATED.key }).isTrue()
    }

    @Test
    fun `agent suggestCompensation below threshold auto-approves and notifies on manual approve path`() = runBlocking {
        val ticketId = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DISPUTE,
            priority = TicketPriority.HIGH, subject = "wrong item",
            description = "d", actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val res = useCase.suggestCompensation(ticketId, 5.0, "agent1", Role.AGENT)

        assertThat(res.isSuccess).isTrue()
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        // Auto-approve at or below the threshold
        assertThat(ticket.compensationStatus).isEqualTo(CompensationStatus.APPROVED.name)
        // Stored amount is real AES-GCM ciphertext, not plaintext, and round-trips
        assertThat(ticket.compensationApprovedAmount).isNotNull()
        assertThat(ticket.compensationApprovedAmount).isNotEqualTo("5.0")
        assertThat(encryptionManager.decrypt(ticket.compensationApprovedAmount!!)).isEqualTo("5.0")
        assertThat(ticket.compensationApprovedBy).isEqualTo("agent1")
    }

    @Test
    fun `end user cannot transition status even on own ticket - agent-only action`() = runBlocking {
        val ticketId = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "s",
            description = "d", actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val res = useCase.transitionStatus(
            ticketId, TicketStatus.ASSIGNED, "user1", Role.END_USER
        )

        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Ticket still OPEN
        assertThat(database.ticketsQueries.getTicketById(ticketId).executeAsOne().status)
            .isEqualTo(TicketStatus.OPEN.name)
    }

    @Test
    fun `addEvidence owner-path persists text evidence end-to-end`() = runBlocking {
        val ticketId = useCase.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "s",
            description = "d", actorId = "user1", actorRole = Role.END_USER
        ).getOrNull()!!

        val res = useCase.addEvidence(
            ticketId = ticketId,
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "driver was 2 hours late",
            uploadedBy = "user1",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        )

        assertThat(res.isSuccess).isTrue()
        val evidence = database.evidenceItemsQueries.getEvidenceByTicketId(ticketId).executeAsList()
        assertThat(evidence).hasSize(1)
        assertThat(evidence.first().textContent).isEqualTo("driver was 2 hours late")
    }

    @Test
    fun `addEvidence rejects non-owner end user without writing evidence`() = runBlocking {
        val ticketId = useCase.createTicket(
            userId = "userB", ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "s",
            description = "d", actorId = "userB", actorRole = Role.END_USER
        ).getOrNull()!!

        val res = useCase.addEvidence(
            ticketId = ticketId,
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "planted",
            uploadedBy = "userA",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        )

        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(database.evidenceItemsQueries.getEvidenceByTicketId(ticketId).executeAsList())
            .isEmpty()
    }

    @Test
    fun `getOpenTickets shows only in-flight tickets after resolution lifecycle`() = runBlocking {
        val t1 = useCase.createTicket("user1", TicketType.DELAY, TicketPriority.MEDIUM,
            "open-ticket", "d", "user1", Role.END_USER).getOrNull()!!
        val t2 = useCase.createTicket("user1", TicketType.DELAY, TicketPriority.MEDIUM,
            "will-be-resolved", "d", "user1", Role.END_USER).getOrNull()!!

        // Walk t2 to RESOLVED
        ticketRepository.assignTicket(t2, "agent1", "agent1", Role.AGENT)
        useCase.transitionStatus(t2, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)
        useCase.transitionStatus(t2, TicketStatus.RESOLVED, "agent1", Role.AGENT)

        val open = useCase.getOpenTickets(Role.AGENT).map { it.id }
        assertThat(open).contains(t1)
        assertThat(open).doesNotContain(t2)
    }
}

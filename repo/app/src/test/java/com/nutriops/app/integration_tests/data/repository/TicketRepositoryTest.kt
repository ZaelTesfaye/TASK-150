package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.EvidenceType
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketStatus
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct CRUD + query integration tests for [TicketRepository].
 *
 * Complements [TicketOrderTransactionTest] (audit ordering, SLA timestamps,
 * compensation workflow) — this file focuses on query behavior, lifecycle
 * filtering and cross-user isolation of reads.
 */
class TicketRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var repository: TicketRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        encryptionManager = JvmEncryptionManager()
        repository = TicketRepository(database, auditManager, encryptionManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Create + retrieve by id ──

    @Test
    fun `create and retrieve by id returns equal data including status, priority, subject`(): Unit = runBlocking {
        val id = createTicket(
            userId = "user1", subject = "Late delivery",
            type = TicketType.DELAY, priority = TicketPriority.HIGH
        )
        val stored = repository.getTicketById(id)!!

        assertThat(stored.id).isEqualTo(id)
        assertThat(stored.userId).isEqualTo("user1")
        assertThat(stored.agentId).isNull()
        assertThat(stored.ticketType).isEqualTo("DELAY")
        assertThat(stored.status).isEqualTo(TicketStatus.OPEN.name)
        assertThat(stored.priority).isEqualTo(TicketPriority.HIGH.name)
        assertThat(stored.subject).isEqualTo("Late delivery")
        assertThat(stored.compensationStatus).isEqualTo("NONE")
    }

    @Test
    fun `SLA first-response and resolution deadlines are persisted`(): Unit = runBlocking {
        val id = createTicket("user1")
        val ticket = repository.getTicketById(id)!!

        // Format: ISO_LOCAL_DATE_TIME → YYYY-MM-DDTHH:MM:SS
        assertThat(ticket.slaFirstResponseDue).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")
        assertThat(ticket.slaResolutionDue).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")
        assertThat(ticket.slaFirstResponseAt).isNull()
        assertThat(ticket.slaResolvedAt).isNull()
    }

    @Test
    fun `getTicketById returns null for unknown id`(): Unit = runBlocking {
        assertThat(repository.getTicketById("missing")).isNull()
    }

    // ── Status transitions ──

    @Test
    fun `transitionTicketStatus persists new status`(): Unit = runBlocking {
        val id = createTicket("user1")
        repository.assignTicket(id, "agent1", "agent1", Role.AGENT)
        val res = repository.transitionTicketStatus(id, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)

        assertThat(res.isSuccess).isTrue()
        val ticket = repository.getTicketById(id)!!
        assertThat(ticket.status).isEqualTo(TicketStatus.IN_PROGRESS.name)
        assertThat(ticket.slaFirstResponseAt).isNotNull()
    }

    @Test
    fun `invalid status transition is rejected and status unchanged`(): Unit = runBlocking {
        val id = createTicket("user1")

        val res = repository.transitionTicketStatus(id, TicketStatus.RESOLVED, "agent1", Role.AGENT)

        assertThat(res.isFailure).isTrue()
        assertThat(repository.getTicketById(id)!!.status).isEqualTo(TicketStatus.OPEN.name)
    }

    @Test
    fun `transitionTicketStatus fails for unknown ticket id`(): Unit = runBlocking {
        val res = repository.transitionTicketStatus("missing", TicketStatus.ASSIGNED, "agent1", Role.AGENT)
        assertThat(res.isFailure).isTrue()
    }

    // ── assignTicket ──

    @Test
    fun `assignTicket persists agent id and moves status to ASSIGNED`(): Unit = runBlocking {
        val id = createTicket("user1")

        val res = repository.assignTicket(id, "agent1", "admin1", Role.ADMINISTRATOR)

        assertThat(res.isSuccess).isTrue()
        val ticket = repository.getTicketById(id)!!
        assertThat(ticket.agentId).isEqualTo("agent1")
        assertThat(ticket.status).isEqualTo(TicketStatus.ASSIGNED.name)
    }

    @Test
    fun `assignTicket fails for unknown ticket id`(): Unit = runBlocking {
        val res = repository.assignTicket("missing", "agent1", "admin1", Role.ADMINISTRATOR)
        assertThat(res.isFailure).isTrue()
    }

    // ── Filtered queries ──

    @Test
    fun `getTicketsByUserId returns only that users tickets`(): Unit = runBlocking {
        createTicket("userA", subject = "A1")
        createTicket("userA", subject = "A2")
        createTicket("userB", subject = "B1")

        val a = repository.getTicketsByUserId("userA")
        val b = repository.getTicketsByUserId("userB")

        assertThat(a).hasSize(2)
        assertThat(b).hasSize(1)
        assertThat(a.all { it.userId == "userA" }).isTrue()
        assertThat(b.first().subject).isEqualTo("B1")
    }

    @Test
    fun `getOpenTickets excludes RESOLVED and CLOSED tickets`(): Unit = runBlocking {
        val open = createTicket("user1", subject = "open")
        val progressing = createTicket("user1", subject = "wip")
        val toBeResolved = createTicket("user1", subject = "done")

        // Walk "done" through ASSIGNED → IN_PROGRESS → RESOLVED
        repository.assignTicket(toBeResolved, "agent1", "agent1", Role.AGENT)
        repository.transitionTicketStatus(toBeResolved, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)
        repository.transitionTicketStatus(toBeResolved, TicketStatus.RESOLVED, "agent1", Role.AGENT)

        repository.assignTicket(progressing, "agent1", "agent1", Role.AGENT)
        repository.transitionTicketStatus(progressing, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)

        val ids = repository.getOpenTickets().map { it.id }
        assertThat(ids).contains(open)
        assertThat(ids).contains(progressing)
        assertThat(ids).doesNotContain(toBeResolved)
    }

    @Test
    fun `getTicketsByAgentId returns only tickets assigned to that agent`(): Unit = runBlocking {
        val t1 = createTicket("user1", subject = "t1")
        val t2 = createTicket("user2", subject = "t2")
        repository.assignTicket(t1, "agentA", "agentA", Role.AGENT)
        repository.assignTicket(t2, "agentB", "agentB", Role.AGENT)

        val a = repository.getTicketsByAgentId("agentA")
        val b = repository.getTicketsByAgentId("agentB")

        assertThat(a.map { it.id }).containsExactly(t1)
        assertThat(b.map { it.id }).containsExactly(t2)
    }

    @Test
    fun `fetching tickets for a user with none returns an empty list, not null`(): Unit = runBlocking {
        val result = repository.getTicketsByUserId("never-filed-a-ticket")
        assertThat(result).isEmpty()
    }

    // ── Evidence attachment ──

    @Test
    fun `addEvidence persists TEXT evidence linked to the ticket`(): Unit = runBlocking {
        val ticketId = createTicket("user1")
        val evidenceId = repository.addEvidence(
            ticketId = ticketId,
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "I waited 3 hours",
            uploadedBy = "user1",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        ).getOrNull()!!

        val evidence = repository.getEvidenceByTicketId(ticketId)
        assertThat(evidence).hasSize(1)
        assertThat(evidence.first().id).isEqualTo(evidenceId)
        assertThat(evidence.first().evidenceType).isEqualTo(EvidenceType.TEXT.name)
        assertThat(evidence.first().textContent).isEqualTo("I waited 3 hours")
    }

    @Test
    fun `addEvidence rejects IMAGE type without contentUri`(): Unit = runBlocking {
        val ticketId = createTicket("user1")
        val res = repository.addEvidence(
            ticketId = ticketId,
            evidenceType = EvidenceType.IMAGE,
            contentUri = null,
            textContent = null,
            uploadedBy = "user1",
            fileSizeBytes = 1024L,
            actorRole = Role.END_USER
        )
        assertThat(res.isFailure).isTrue()
        assertThat(repository.getEvidenceByTicketId(ticketId)).isEmpty()
    }

    // ── helpers ──

    private suspend fun createTicket(
        userId: String,
        subject: String = "subject",
        type: TicketType = TicketType.DELAY,
        priority: TicketPriority = TicketPriority.MEDIUM
    ): String = repository.createTicket(
        userId = userId,
        ticketType = type,
        priority = priority,
        subject = subject,
        description = "desc",
        actorId = userId,
        actorRole = Role.END_USER
    ).getOrNull()!!
}

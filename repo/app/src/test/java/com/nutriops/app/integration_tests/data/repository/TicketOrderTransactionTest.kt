package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.OrderRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests asserting atomic writes, audit log entries,
 * SLA timestamps, and compensation workflows against a real in-memory SQLDelight
 * database with real AES-256-GCM encryption. No mocks on the production
 * boundary — the [JvmEncryptionManager] is a test-scoped subclass of
 * [EncryptionManager] that uses a deterministic in-memory AES key instead of
 * the Android Keystore (unavailable on the host JVM).
 */
class TicketOrderTransactionTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var ticketRepository: TicketRepository
    private lateinit var orderRepository: OrderRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)

        encryptionManager = JvmEncryptionManager()

        auditManager = AuditManager(database)
        ticketRepository = TicketRepository(database, auditManager, encryptionManager)
        orderRepository = OrderRepository(database, auditManager, encryptionManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Ticket creation ──

    @Test
    fun `ticket creation persists ticket and audit event atomically`() = runBlocking {
        val result = ticketRepository.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.HIGH, subject = "Late delivery",
            description = "Package was late", actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
        val ticketId = result.getOrNull()!!

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOneOrNull()
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.status).isEqualTo("OPEN")
        assertThat(ticket.ticketType).isEqualTo("DELAY")
        assertThat(ticket.subject).isEqualTo("Late delivery")
        assertThat(ticket.slaFirstResponseDue).isNotEmpty()
        assertThat(ticket.slaResolutionDue).isNotEmpty()

        val audit = auditManager.getAuditTrail("Ticket", ticketId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
        assertThat(audit.first().actorId).isEqualTo("user1")
    }

    @Test
    fun `ticket creation sets compensation status to NONE`() = runBlocking {
        val result = ticketRepository.createTicket(
            userId = "user1", ticketType = TicketType.DISPUTE,
            priority = TicketPriority.CRITICAL, subject = "Wrong item",
            description = "Got wrong item", actorId = "user1", actorRole = Role.END_USER
        )
        val ticket = database.ticketsQueries.getTicketById(result.getOrNull()!!).executeAsOne()
        assertThat(ticket.compensationStatus).isEqualTo(CompensationStatus.NONE.name)
        assertThat(ticket.compensationSuggestedAmount).isNull()
        assertThat(ticket.compensationApprovedAmount).isNull()
    }

    // ── Ticket status transition ──

    @Test
    fun `transition to IN_PROGRESS writes audit with STATUS_CHANGE and records first-response timestamp`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        // OPEN → ASSIGNED → IN_PROGRESS
        ticketRepository.transitionTicketStatus(ticketId, TicketStatus.ASSIGNED, "agent1", Role.AGENT)
        val res = ticketRepository.transitionTicketStatus(ticketId, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)
        assertThat(res.isSuccess).isTrue()

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo("IN_PROGRESS")
        assertThat(ticket.slaFirstResponseAt).isNotNull()

        val audit = auditManager.getAuditTrail("Ticket", ticketId)
            .filter { it.action == AuditAction.STATUS_CHANGE.name }
        assertThat(audit).isNotEmpty()
        assertThat(audit.last().newState).contains("IN_PROGRESS")
    }

    @Test
    fun `transition to RESOLVED records resolution timestamp`() = runBlocking {
        val ticketId = createOpenTicket("user1")
        ticketRepository.transitionTicketStatus(ticketId, TicketStatus.ASSIGNED, "agent1", Role.AGENT)
        ticketRepository.transitionTicketStatus(ticketId, TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)
        ticketRepository.transitionTicketStatus(ticketId, TicketStatus.RESOLVED, "agent1", Role.AGENT)

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo("RESOLVED")
        assertThat(ticket.slaResolvedAt).isNotNull()
    }

    @Test
    fun `invalid status transition is rejected`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        // OPEN → RESOLVED is not a valid transition
        val result = ticketRepository.transitionTicketStatus(
            ticketId, TicketStatus.RESOLVED, "agent1", Role.AGENT
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Cannot transition")
        // Ticket status unchanged
        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo("OPEN")
    }

    // ── Compensation workflow ──

    @Test
    fun `compensation at or below auto-approve threshold transitions to APPROVED`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        val res = ticketRepository.suggestCompensation(
            ticketId = ticketId, amount = 5.0, actorId = "agent1", actorRole = Role.AGENT
        )
        assertThat(res.isSuccess).isTrue()

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.compensationStatus).isEqualTo(CompensationStatus.APPROVED.name)
        // Stored values are real AES-GCM ciphertext and round-trip through the cipher
        assertThat(ticket.compensationSuggestedAmount).isNotEqualTo("5.0")
        assertThat(encryptionManager.decrypt(ticket.compensationSuggestedAmount!!)).isEqualTo("5.0")
        assertThat(encryptionManager.decrypt(ticket.compensationApprovedAmount!!)).isEqualTo("5.0")
        assertThat(ticket.compensationApprovedBy).isEqualTo("agent1")
    }

    @Test
    fun `compensation above threshold transitions to PENDING_APPROVAL`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        ticketRepository.suggestCompensation(
            ticketId = ticketId, amount = 15.0, actorId = "agent1", actorRole = Role.AGENT
        )

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.compensationStatus).isEqualTo(CompensationStatus.PENDING_APPROVAL.name)
        assertThat(ticket.compensationSuggestedAmount).isNotEqualTo("15.0")
        assertThat(encryptionManager.decrypt(ticket.compensationSuggestedAmount!!)).isEqualTo("15.0")
        assertThat(ticket.compensationApprovedAmount).isNull()
    }

    @Test
    fun `compensation suggestion writes a COMPENSATION_SUGGEST audit event`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        ticketRepository.suggestCompensation(ticketId, 8.0, "agent1", Role.AGENT)

        val audit = auditManager.getAuditTrail("Ticket", ticketId)
            .filter { it.action == AuditAction.COMPENSATION_SUGGEST.name }
        assertThat(audit).hasSize(1)
        assertThat(audit.first().actorId).isEqualTo("agent1")
    }

    @Test
    fun `compensation below minimum is rejected`() = runBlocking {
        val ticketId = createOpenTicket("user1")

        val result = ticketRepository.suggestCompensation(ticketId, 0.5, "agent1", Role.AGENT)

        assertThat(result.isFailure).isTrue()
        // No audit event should have been written for the failed suggestion
        val audit = auditManager.getAuditTrail("Ticket", ticketId)
            .filter { it.action == AuditAction.COMPENSATION_SUGGEST.name }
        assertThat(audit).isEmpty()
    }

    // ── Order creation ──

    @Test
    fun `order creation persists encrypted amount and writes audit`() = runBlocking {
        val res = orderRepository.createOrder(
            userId = "user1", totalAmount = 99.99,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(res.isSuccess).isTrue()
        val orderId = res.getOrNull()!!

        val order = database.ordersQueries.getOrderById(orderId).executeAsOneOrNull()
        assertThat(order).isNotNull()
        assertThat(order!!.totalAmount).isNotEqualTo("99.99")
        assertThat(encryptionManager.decrypt(order.totalAmount)).isEqualTo("99.99")
        assertThat(order.userId).isEqualTo("user1")

        val audit = auditManager.getAuditTrail("Order", orderId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    // ── Decrypt helpers ──

    @Test
    fun `ticket decryptAmount decrypts real ciphertext produced by EncryptionManager`() {
        val ciphertext = encryptionManager.encrypt("100.0")
        val amount = ticketRepository.decryptAmount(ciphertext)
        assertThat(amount).isEqualTo(100.0)
    }

    @Test
    fun `ticket decryptAmount returns null for null input`() {
        assertThat(ticketRepository.decryptAmount(null)).isNull()
    }

    @Test
    fun `ticket decryptAmount falls back to plain text for legacy unencrypted data`() {
        val amount = ticketRepository.decryptAmount("15.5")
        assertThat(amount).isEqualTo(15.5)
    }

    @Test
    fun `order decryptAmount decrypts real ciphertext produced by EncryptionManager`() {
        val ciphertext = encryptionManager.encrypt("50.0")
        val amount = orderRepository.decryptAmount(ciphertext)
        assertThat(amount).isEqualTo(50.0)
    }

    @Test
    fun `order decryptNotes falls back to plain text for legacy data`() {
        val notes = orderRepository.decryptNotes("plain text notes")
        assertThat(notes).isEqualTo("plain text notes")
    }

    // ── helpers ──

    private suspend fun createOpenTicket(userId: String): String {
        return ticketRepository.createTicket(
            userId = userId, ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM, subject = "subj",
            description = "desc", actorId = userId, actorRole = Role.END_USER
        ).getOrNull()!!
    }
}

package com.nutriops.app.integration_tests.data.repository

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.local.Tickets
import com.nutriops.app.data.local.TicketsQueries
import com.nutriops.app.data.local.EvidenceItemsQueries
import com.nutriops.app.data.local.OrdersQueries
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.data.repository.OrderRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.security.EncryptionManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests asserting atomic writes, audit log entries
 * for ticket status changes, SLA timestamps, and compensation workflows.
 */
class TicketOrderTransactionTest {

    private val database: NutriOpsDatabase = mockk(relaxed = true)
    private val auditManager: AuditManager = mockk(relaxed = true)
    private val encryptionManager: EncryptionManager = mockk(relaxed = true)
    private val ticketQueries: TicketsQueries = mockk(relaxed = true)
    private val evidenceQueries: EvidenceItemsQueries = mockk(relaxed = true)
    private val ordersQueries: OrdersQueries = mockk(relaxed = true)
    private lateinit var ticketRepository: TicketRepository
    private lateinit var orderRepository: OrderRepository

    @Before
    fun setup() {
        every { database.ticketsQueries } returns ticketQueries
        every { database.evidenceItemsQueries } returns evidenceQueries
        every { database.ordersQueries } returns ordersQueries

        // Encryption pass-through
        every { encryptionManager.encrypt(any()) } answers { "ENC(${firstArg<String>()})" }
        every { encryptionManager.decrypt(any()) } answers {
            val v = firstArg<String>()
            if (v.startsWith("ENC(")) v.removePrefix("ENC(").removeSuffix(")") else throw IllegalArgumentException("Not encrypted")
        }

        // logWithTransaction executes the lambda inside a transaction
        every { auditManager.logWithTransaction(
            entityType = any(), entityId = any(), action = any(),
            actorId = any(), actorRole = any(), details = any(),
            previousState = any(), newState = any(),
            transactionBlock = captureLambda()
        ) } answers { lambda<() -> Unit>().invoke() }

        ticketRepository = TicketRepository(database, auditManager, encryptionManager)
        orderRepository = OrderRepository(database, auditManager, encryptionManager)
    }

    // ── Ticket creation ──

    @Test
    fun `ticket creation writes audit with CREATE action`() = runBlocking {
        ticketRepository.createTicket(
            userId = "user1", ticketType = TicketType.DELAY,
            priority = TicketPriority.HIGH, subject = "Late delivery",
            description = "Package was late", actorId = "user1", actorRole = Role.END_USER
        )

        verify { auditManager.logWithTransaction(
            entityType = "Ticket", entityId = any(),
            action = AuditAction.CREATE,
            actorId = "user1", actorRole = Role.END_USER,
            details = any(), previousState = any(), newState = any(),
            transactionBlock = any()
        ) }
    }

    @Test
    fun `ticket creation sets SLA first-response and resolution deadlines`() = runBlocking {
        ticketRepository.createTicket(
            userId = "user1", ticketType = TicketType.DISPUTE,
            priority = TicketPriority.CRITICAL, subject = "Wrong item",
            description = "Got wrong item", actorId = "user1", actorRole = Role.END_USER
        )

        verify { ticketQueries.insertTicket(
            id = any(), userId = "user1", agentId = null,
            ticketType = "DISPUTE", status = "OPEN", priority = "CRITICAL",
            subject = "Wrong item", description = "Got wrong item",
            slaFirstResponseDue = any(), slaResolutionDue = any(),
            slaFirstResponseAt = null, slaResolvedAt = null,
            slaPausedAt = null, slaTotalPauseDurationMinutes = 0L,
            compensationSuggestedAmount = null, compensationApprovedAmount = null,
            compensationApprovedBy = null, compensationStatus = "NONE",
            createdAt = any(), updatedAt = any()
        ) }
    }

    // ── Ticket status transition ──

    @Test
    fun `status transition writes audit with STATUS_CHANGE action`() = runBlocking {
        val mockTicket = mockk<Tickets>(relaxed = true)
        every { mockTicket.status } returns "ASSIGNED"
        every { mockTicket.slaFirstResponseAt } returns null
        every { ticketQueries.getTicketById(any()).executeAsOneOrNull() } returns mockTicket

        ticketRepository.transitionTicketStatus(
            ticketId = "t1", newStatus = TicketStatus.IN_PROGRESS,
            actorId = "agent1", actorRole = Role.AGENT
        )

        verify { auditManager.logWithTransaction(
            entityType = "Ticket", entityId = "t1",
            action = AuditAction.STATUS_CHANGE,
            actorId = "agent1", actorRole = Role.AGENT,
            details = any(),
            previousState = match { it?.contains("ASSIGNED") == true },
            newState = match { it?.contains("IN_PROGRESS") == true },
            transactionBlock = any()
        ) }
    }

    @Test
    fun `transition to IN_PROGRESS records first-response SLA timestamp`() = runBlocking {
        val mockTicket = mockk<Tickets>(relaxed = true)
        every { mockTicket.status } returns "ASSIGNED"
        every { mockTicket.slaFirstResponseAt } returns null
        every { ticketQueries.getTicketById(any()).executeAsOneOrNull() } returns mockTicket

        ticketRepository.transitionTicketStatus(
            ticketId = "t1", newStatus = TicketStatus.IN_PROGRESS,
            actorId = "agent1", actorRole = Role.AGENT
        )

        verify { ticketQueries.updateTicketSlaFirstResponse(any(), any(), "t1") }
    }

    @Test
    fun `transition to RESOLVED records resolution SLA timestamp`() = runBlocking {
        val mockTicket = mockk<Tickets>(relaxed = true)
        every { mockTicket.status } returns "IN_PROGRESS"
        every { mockTicket.slaFirstResponseAt } returns "2026-01-01T10:00:00"
        every { ticketQueries.getTicketById(any()).executeAsOneOrNull() } returns mockTicket

        ticketRepository.transitionTicketStatus(
            ticketId = "t1", newStatus = TicketStatus.RESOLVED,
            actorId = "agent1", actorRole = Role.AGENT
        )

        verify { ticketQueries.updateTicketResolved(any(), any(), "t1") }
    }

    @Test
    fun `invalid status transition returns failure`() = runBlocking {
        val mockTicket = mockk<Tickets>(relaxed = true)
        every { mockTicket.status } returns "OPEN"
        every { ticketQueries.getTicketById(any()).executeAsOneOrNull() } returns mockTicket

        val result = ticketRepository.transitionTicketStatus(
            ticketId = "t1", newStatus = TicketStatus.RESOLVED,
            actorId = "agent1", actorRole = Role.AGENT
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Cannot transition")
    }

    // ── Compensation workflow ──

    @Test
    fun `compensation suggestion encrypts amount before writing`() = runBlocking {
        ticketRepository.suggestCompensation(
            ticketId = "t1", amount = 8.0, actorId = "agent1", actorRole = Role.AGENT
        )

        verify { encryptionManager.encrypt("8.0") }
        verify { auditManager.logWithTransaction(
            entityType = "Ticket", entityId = "t1",
            action = AuditAction.COMPENSATION_SUGGEST,
            actorId = "agent1", actorRole = Role.AGENT,
            details = any(), previousState = any(), newState = any(),
            transactionBlock = any()
        ) }
    }

    @Test
    fun `compensation auto-approves when amount at or below threshold`() = runBlocking {
        ticketRepository.suggestCompensation(
            ticketId = "t1", amount = 5.0, actorId = "agent1", actorRole = Role.AGENT
        )

        verify { ticketQueries.updateTicketCompensation(
            compensationSuggestedAmount = "ENC(5.0)",
            compensationApprovedAmount = "ENC(5.0)",
            compensationApprovedBy = "agent1",
            compensationStatus = "APPROVED",
            updatedAt = any(), id = "t1"
        ) }
    }

    @Test
    fun `compensation above threshold goes to PENDING_APPROVAL`() = runBlocking {
        ticketRepository.suggestCompensation(
            ticketId = "t1", amount = 15.0, actorId = "agent1", actorRole = Role.AGENT
        )

        verify { ticketQueries.updateTicketCompensation(
            compensationSuggestedAmount = "ENC(15.0)",
            compensationApprovedAmount = null,
            compensationApprovedBy = null,
            compensationStatus = "PENDING_APPROVAL",
            updatedAt = any(), id = "t1"
        ) }
    }

    // ── Order creation with encryption ──

    @Test
    fun `order creation encrypts amount and notes`() = runBlocking {
        orderRepository.createOrder(
            userId = "user1", totalAmount = 99.99,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        verify { encryptionManager.encrypt("99.99") }
        verify { encryptionManager.encrypt("") }
    }

    @Test
    fun `order creation writes audit log`() = runBlocking {
        orderRepository.createOrder(
            userId = "user1", totalAmount = 50.0,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        verify { auditManager.logWithTransaction(
            entityType = "Order", entityId = any(),
            action = AuditAction.CREATE,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR,
            details = any(), previousState = any(), newState = any(),
            transactionBlock = any()
        ) }
    }

    // ── Decrypt helpers ──

    @Test
    fun `decryptAmount decrypts encrypted value`() {
        val amount = ticketRepository.decryptAmount("ENC(100.0)")
        assertThat(amount).isEqualTo(100.0)
    }

    @Test
    fun `decryptAmount returns null for null input`() {
        assertThat(ticketRepository.decryptAmount(null)).isNull()
    }

    @Test
    fun `decryptAmount falls back to plain text for legacy unencrypted data`() {
        val amount = ticketRepository.decryptAmount("15.5")
        assertThat(amount).isEqualTo(15.5)
    }

    @Test
    fun `order decryptAmount decrypts encrypted value`() {
        val amount = orderRepository.decryptAmount("ENC(50.0)")
        assertThat(amount).isEqualTo(50.0)
    }

    @Test
    fun `order decryptNotes decrypts encrypted value`() {
        val notes = orderRepository.decryptNotes("ENC(My notes)")
        assertThat(notes).isEqualTo("My notes")
    }

    @Test
    fun `order decryptNotes falls back to plain text for legacy data`() {
        val notes = orderRepository.decryptNotes("plain text notes")
        assertThat(notes).isEqualTo("plain text notes")
    }
}

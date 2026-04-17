package com.nutriops.app.unit_tests.domain.usecase.ticket

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.EvidenceItems
import com.nutriops.app.data.local.Tickets
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ManageTicketUseCaseTest {

    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: ManageTicketUseCase

    @Before
    fun setup() {
        ticketRepository = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        useCase = ManageTicketUseCase(ticketRepository, messageRepository)
    }

    // ── createTicket ──

    @Test
    fun `createTicket succeeds for end user and sends notification`() = runBlocking {
        coEvery {
            ticketRepository.createTicket(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("t1")

        val result = useCase.createTicket(
            userId = "user1",
            ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM,
            subject = "Late package",
            description = "Expected yesterday",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("t1")
        coVerify {
            messageRepository.sendMessage(
                userId = "user1",
                templateId = null,
                title = match { it.contains("Late package") },
                body = any(),
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_CREATED.key
            )
        }
    }

    @Test
    fun `createTicket rejects blank subject before touching repo`() = runBlocking {
        val result = useCase.createTicket(
            userId = "user1",
            ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM,
            subject = "   ",
            description = "desc",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) {
            ticketRepository.createTicket(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createTicket does not send notification when repo fails`() = runBlocking {
        coEvery {
            ticketRepository.createTicket(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(RuntimeException("db error"))

        val result = useCase.createTicket(
            userId = "user1",
            ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM,
            subject = "Subject",
            description = "desc",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) {
            messageRepository.sendMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ── transitionStatus ──

    @Test
    fun `transitionStatus succeeds for agent and notifies ticket owner`() = runBlocking {
        val ticket = mockk<Tickets> {
            every { userId } returns "user1"
            every { subject } returns "Late package"
        }
        coEvery {
            ticketRepository.transitionTicketStatus("t1", TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)
        } returns Result.success(Unit)
        coEvery { ticketRepository.getTicketById("t1") } returns ticket

        val result = useCase.transitionStatus("t1", TicketStatus.IN_PROGRESS, "agent1", Role.AGENT)

        assertThat(result.isSuccess).isTrue()
        coVerify {
            messageRepository.sendMessage(
                userId = "user1",
                templateId = null,
                title = "Ticket Updated",
                body = match { it.contains("IN_PROGRESS") },
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_UPDATED.key
            )
        }
    }

    @Test
    fun `transitionStatus denied for end user`() = runBlocking {
        val result = useCase.transitionStatus("t1", TicketStatus.IN_PROGRESS, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    // ── addEvidence ──

    @Test
    fun `addEvidence rejects image evidence without content URI`() = runBlocking {
        val ticket = mockk<Tickets> { every { userId } returns "user1" }
        coEvery { ticketRepository.getTicketById("t1") } returns ticket
        coEvery {
            ticketRepository.addEvidence("t1", EvidenceType.IMAGE, null, null, "user1", 500L, Role.END_USER)
        } returns Result.failure(IllegalArgumentException("Image evidence requires a content URI"))

        val result = useCase.addEvidence(
            ticketId = "t1",
            evidenceType = EvidenceType.IMAGE,
            contentUri = null,
            textContent = null,
            uploadedBy = "user1",
            fileSizeBytes = 500L,
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 1) {
            ticketRepository.addEvidence("t1", EvidenceType.IMAGE, null, null, "user1", 500L, Role.END_USER)
        }
    }

    @Test
    fun `addEvidence fails when ticket not found`() = runBlocking {
        coEvery { ticketRepository.getTicketById("missing") } returns null

        val result = useCase.addEvidence(
            ticketId = "missing",
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "hello",
            uploadedBy = "user1",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `addEvidence succeeds for owner uploading text evidence`() = runBlocking {
        val ticket = mockk<Tickets> { every { userId } returns "user1" }
        coEvery { ticketRepository.getTicketById("t1") } returns ticket
        coEvery {
            ticketRepository.addEvidence("t1", EvidenceType.TEXT, null, "hello", "user1", null, Role.END_USER)
        } returns Result.success("ev1")

        val result = useCase.addEvidence(
            ticketId = "t1",
            evidenceType = EvidenceType.TEXT,
            contentUri = null,
            textContent = "hello",
            uploadedBy = "user1",
            fileSizeBytes = null,
            actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("ev1")
    }

    // ── approveCompensation ──

    @Test
    fun `approveCompensation denied for end user`() = runBlocking {
        val result = useCase.approveCompensation("t1", 8.0, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `approveCompensation on success notifies ticket owner`() = runBlocking {
        val ticket = mockk<Tickets> {
            every { userId } returns "user1"
            every { subject } returns "Subject"
        }
        coEvery {
            ticketRepository.approveCompensation("t1", 12.0, "agent1", Role.AGENT)
        } returns Result.success(Unit)
        coEvery { ticketRepository.getTicketById("t1") } returns ticket

        val result = useCase.approveCompensation("t1", 12.0, "agent1", Role.AGENT)

        assertThat(result.isSuccess).isTrue()
        coVerify {
            messageRepository.sendMessage(
                userId = "user1",
                templateId = null,
                title = "Compensation Approved",
                body = match { it.contains("12.0") },
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.COMPENSATION_APPROVED.key
            )
        }
    }

    // ── Read/query paths ──

    @Test
    fun `getTicketDetails returns null for end user trying to read others ticket`() = runBlocking {
        val ticket = mockk<Tickets> { every { userId } returns "userB" }
        coEvery { ticketRepository.getTicketById("t1") } returns ticket

        val result = useCase.getTicketDetails("t1", "userA", Role.END_USER)

        assertThat(result).isNull()
    }

    @Test
    fun `getTicketDetails returns ticket for owner`() = runBlocking {
        val ticket = mockk<Tickets> { every { userId } returns "user1" }
        coEvery { ticketRepository.getTicketById("t1") } returns ticket

        val result = useCase.getTicketDetails("t1", "user1", Role.END_USER)

        assertThat(result).isSameInstanceAs(ticket)
    }

    @Test
    fun `getEvidence returns list for agent`() = runBlocking {
        val evidence = listOf<EvidenceItems>(mockk(relaxed = true))
        coEvery { ticketRepository.getEvidenceByTicketId("t1") } returns evidence

        val result = useCase.getEvidence("t1", "agent1", Role.AGENT)

        assertThat(result).isEqualTo(evidence)
    }

    @Test
    fun `getAllowedTransitions returns state machine transitions for current status`() {
        val transitions = useCase.getAllowedTransitions(TicketStatus.OPEN)
        assertThat(transitions).isEqualTo(TicketStatus.OPEN.allowedTransitions())
    }
}

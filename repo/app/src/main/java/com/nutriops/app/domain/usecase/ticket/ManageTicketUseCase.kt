package com.nutriops.app.domain.usecase.ticket

import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

/**
 * Complete ticket lifecycle management:
 * - Create delay/dispute/lost-item tickets
 * - SLA clock management (4 business hour first response, 3 day resolution)
 * - Evidence upload (image/text only)
 * - Compensation suggestion with auto-approve threshold ($10)
 * - Agent approval workflow for amounts above threshold
 * - Status transition enforcement
 */
class ManageTicketUseCase @Inject constructor(
    private val ticketRepository: TicketRepository,
    private val messageRepository: MessageRepository
) {
    suspend fun createTicket(
        userId: String,
        ticketType: TicketType,
        priority: TicketPriority,
        subject: String,
        description: String,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.CREATE_TICKET)
            .getOrElse { return Result.failure(it) }

        if (subject.isBlank()) {
            return Result.failure(IllegalArgumentException("Subject is required"))
        }

        val result = ticketRepository.createTicket(userId, ticketType, priority, subject, description, actorId, actorRole)

        // Trigger message on ticket creation
        result.onSuccess { ticketId ->
            messageRepository.sendMessage(
                userId = userId,
                templateId = null,
                title = "Ticket Created: $subject",
                body = "Your ${ticketType.name.lowercase()} ticket has been created. We'll respond within 4 business hours.",
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_CREATED.key
            )
        }

        return result
    }

    suspend fun assignToAgent(
        ticketId: String,
        agentId: String,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.ASSIGN_TICKET)
            .getOrElse { return Result.failure(it) }

        return ticketRepository.assignTicket(ticketId, agentId, actorId, actorRole)
    }

    suspend fun transitionStatus(
        ticketId: String,
        newStatus: TicketStatus,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_TICKETS)
            .getOrElse { return Result.failure(it) }

        val result = ticketRepository.transitionTicketStatus(ticketId, newStatus, actorId, actorRole)

        // Send notification on status changes
        result.onSuccess {
            val ticket = ticketRepository.getTicketById(ticketId)
            if (ticket != null) {
                messageRepository.sendMessage(
                    userId = ticket.userId,
                    templateId = null,
                    title = "Ticket Updated",
                    body = "Your ticket '${ticket.subject}' status changed to ${newStatus.name}.",
                    messageType = MessageType.NOTIFICATION.name,
                    triggerEvent = TriggerEvent.TICKET_UPDATED.key
                )
            }
        }

        return result
    }

    suspend fun addEvidence(
        ticketId: String,
        evidenceType: EvidenceType,
        contentUri: String?,
        textContent: String?,
        uploadedBy: String,
        fileSizeBytes: Long?,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.UPLOAD_EVIDENCE)
            .getOrElse { return Result.failure(it) }

        val ticket = ticketRepository.getTicketById(ticketId)
            ?: return Result.failure(IllegalArgumentException("Ticket not found"))
        RbacManager.checkObjectOwnership(uploadedBy, ticket.userId, actorRole)
            .getOrElse { return Result.failure(it) }

        // Enforce image/text only
        if (evidenceType != EvidenceType.IMAGE && evidenceType != EvidenceType.TEXT) {
            return Result.failure(IllegalArgumentException("Only image and text evidence are supported"))
        }

        return ticketRepository.addEvidence(ticketId, evidenceType, contentUri, textContent, uploadedBy, fileSizeBytes, actorRole)
    }

    suspend fun suggestCompensation(
        ticketId: String,
        amount: Double,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.SUGGEST_COMPENSATION)
            .getOrElse { return Result.failure(it) }

        return ticketRepository.suggestCompensation(ticketId, amount, actorId, actorRole)
    }

    suspend fun approveCompensation(
        ticketId: String,
        approvedAmount: Double,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.APPROVE_COMPENSATION)
            .getOrElse { return Result.failure(it) }

        val result = ticketRepository.approveCompensation(ticketId, approvedAmount, actorId, actorRole)

        result.onSuccess {
            val ticket = ticketRepository.getTicketById(ticketId)
            if (ticket != null) {
                messageRepository.sendMessage(
                    userId = ticket.userId,
                    templateId = null,
                    title = "Compensation Approved",
                    body = "A compensation of \$$approvedAmount has been approved for your ticket.",
                    messageType = MessageType.NOTIFICATION.name,
                    triggerEvent = TriggerEvent.COMPENSATION_APPROVED.key
                )
            }
        }

        return result
    }

    suspend fun pauseSla(ticketId: String, actorId: String, actorRole: Role): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_TICKETS)
            .getOrElse { return Result.failure(it) }
        return ticketRepository.pauseSla(ticketId, actorId, actorRole)
    }

    suspend fun resumeSla(ticketId: String, pauseMinutes: Long, actorId: String, actorRole: Role): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_TICKETS)
            .getOrElse { return Result.failure(it) }
        return ticketRepository.resumeSla(ticketId, pauseMinutes, actorId, actorRole)
    }

    suspend fun getTicketDetails(
        ticketId: String, actorId: String, actorRole: Role
    ): com.nutriops.app.data.local.Tickets? {
        val ticket = ticketRepository.getTicketById(ticketId) ?: return null
        if (actorRole == Role.END_USER) {
            RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_TICKETS)
                .getOrElse { return null }
            RbacManager.checkObjectOwnership(actorId, ticket.userId, actorRole)
                .getOrElse { return null }
        } else {
            RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_ALL_TICKETS)
                .getOrElse { return null }
        }
        return ticket
    }

    suspend fun getTicketsByUser(
        userId: String, actorId: String, actorRole: Role
    ): List<com.nutriops.app.data.local.Tickets> {
        if (actorRole == Role.END_USER) {
            RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_TICKETS)
                .getOrElse { return emptyList() }
            RbacManager.checkObjectOwnership(actorId, userId, actorRole)
                .getOrElse { return emptyList() }
        } else {
            RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_ALL_TICKETS)
                .getOrElse { return emptyList() }
        }
        return ticketRepository.getTicketsByUserId(userId)
    }

    suspend fun getTicketsForAgent(
        agentId: String, actorId: String, actorRole: Role
    ): List<com.nutriops.app.data.local.Tickets> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_ALL_TICKETS)
            .getOrElse { return emptyList() }
        return ticketRepository.getTicketsByAgentId(agentId)
    }

    suspend fun getOpenTickets(actorRole: Role): List<com.nutriops.app.data.local.Tickets> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_ALL_TICKETS)
            .getOrElse { return emptyList() }
        return ticketRepository.getOpenTickets()
    }

    suspend fun getEvidence(
        ticketId: String, actorId: String, actorRole: Role
    ): List<com.nutriops.app.data.local.EvidenceItems> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_EVIDENCE)
            .getOrElse {
                // End users can view evidence on their own tickets
                if (actorRole == Role.END_USER) {
                    val ticket = ticketRepository.getTicketById(ticketId) ?: return emptyList()
                    RbacManager.checkObjectOwnership(actorId, ticket.userId, actorRole)
                        .getOrElse { return emptyList() }
                } else {
                    return emptyList()
                }
            }
        return ticketRepository.getEvidenceByTicketId(ticketId)
    }

    fun getAllowedTransitions(currentStatus: TicketStatus): Set<TicketStatus> =
        currentStatus.allowedTransitions()
}

package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.*
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TicketRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager,
    private val encryptionManager: com.nutriops.app.security.EncryptionManager
) {
    private val ticketQueries get() = database.ticketsQueries
    private val evidenceQueries get() = database.evidenceItemsQueries

    suspend fun createTicket(
        userId: String,
        ticketType: TicketType,
        priority: TicketPriority,
        subject: String,
        description: String,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ticketId = UUID.randomUUID().toString()
            val now = LocalDateTime.now()
            val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val slaFirstResponseDue = calculateBusinessHoursDeadline(now, AppConfig.SLA_FIRST_RESPONSE_HOURS)
            val slaResolutionDue = calculateBusinessDaysDeadline(now, AppConfig.SLA_RESOLUTION_DAYS)

            auditManager.logWithTransaction(
                entityType = "Ticket",
                entityId = ticketId,
                action = AuditAction.CREATE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"type":"${ticketType.name}","priority":"${priority.name}","subject":"$subject"}"""
            ) {
                ticketQueries.insertTicket(
                    id = ticketId,
                    userId = userId,
                    agentId = null,
                    ticketType = ticketType.name,
                    status = TicketStatus.OPEN.name,
                    priority = priority.name,
                    subject = subject,
                    description = description,
                    slaFirstResponseDue = slaFirstResponseDue.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    slaResolutionDue = slaResolutionDue.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    slaFirstResponseAt = null,
                    slaResolvedAt = null,
                    slaPausedAt = null,
                    slaTotalPauseDurationMinutes = 0,
                    compensationSuggestedAmount = null,
                    compensationApprovedAmount = null,
                    compensationApprovedBy = null,
                    compensationStatus = CompensationStatus.NONE.name,
                    createdAt = nowStr,
                    updatedAt = nowStr
                )
            }

            AppLogger.info("TicketRepo", "Ticket created: $ticketId type=${ticketType.name}")
            Result.success(ticketId)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to create ticket", e)
            Result.failure(e)
        }
    }

    suspend fun assignTicket(
        ticketId: String,
        agentId: String,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ticket = ticketQueries.getTicketById(ticketId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Ticket not found"))

            val currentStatus = TicketStatus.valueOf(ticket.status)
            if (!currentStatus.canTransitionTo(TicketStatus.ASSIGNED)) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot assign ticket in status ${currentStatus.name}")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Ticket",
                entityId = ticketId,
                action = AuditAction.ASSIGN,
                actorId = actorId,
                actorRole = actorRole,
                previousState = """{"status":"${ticket.status}","agentId":${ticket.agentId?.let { "\"$it\"" }}}""",
                newState = """{"status":"ASSIGNED","agentId":"$agentId"}"""
            ) {
                ticketQueries.updateTicketAgent(agentId, now, ticketId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to assign ticket", e)
            Result.failure(e)
        }
    }

    suspend fun transitionTicketStatus(
        ticketId: String,
        newStatus: TicketStatus,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ticket = ticketQueries.getTicketById(ticketId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Ticket not found"))

            val currentStatus = TicketStatus.valueOf(ticket.status)
            if (!currentStatus.canTransitionTo(newStatus)) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot transition from ${currentStatus.name} to ${newStatus.name}")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Ticket",
                entityId = ticketId,
                action = AuditAction.STATUS_CHANGE,
                actorId = actorId,
                actorRole = actorRole,
                previousState = """{"status":"${currentStatus.name}"}""",
                newState = """{"status":"${newStatus.name}"}"""
            ) {
                ticketQueries.updateTicketStatus(newStatus.name, now, ticketId)

                // Record first response time
                if (newStatus == TicketStatus.IN_PROGRESS && ticket.slaFirstResponseAt == null) {
                    ticketQueries.updateTicketSlaFirstResponse(now, now, ticketId)
                }

                // Record resolution time
                if (newStatus == TicketStatus.RESOLVED) {
                    ticketQueries.updateTicketResolved(now, now, ticketId)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to transition ticket", e)
            Result.failure(e)
        }
    }

    suspend fun addEvidence(
        ticketId: String,
        evidenceType: EvidenceType,
        contentUri: String?,
        textContent: String?,
        uploadedBy: String,
        fileSizeBytes: Long?,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (evidenceType == EvidenceType.IMAGE && contentUri == null) {
                return@withContext Result.failure(IllegalArgumentException("Image evidence requires a content URI"))
            }
            if (evidenceType == EvidenceType.TEXT && textContent == null) {
                return@withContext Result.failure(IllegalArgumentException("Text evidence requires content"))
            }

            val evidenceId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Evidence",
                entityId = evidenceId,
                action = AuditAction.EVIDENCE_UPLOAD,
                actorId = uploadedBy,
                actorRole = actorRole,
                details = """{"ticketId":"$ticketId","type":"${evidenceType.name}"}"""
            ) {
                evidenceQueries.insertEvidenceItem(
                    id = evidenceId,
                    ticketId = ticketId,
                    evidenceType = evidenceType.name,
                    contentUri = contentUri,
                    textContent = textContent,
                    uploadedBy = uploadedBy,
                    fileSizeBytes = fileSizeBytes,
                    createdAt = now
                )
            }

            Result.success(evidenceId)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to add evidence", e)
            Result.failure(e)
        }
    }

    suspend fun suggestCompensation(
        ticketId: String,
        amount: Double,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (amount < AppConfig.COMPENSATION_MIN || amount > AppConfig.COMPENSATION_MAX) {
                return@withContext Result.failure(
                    IllegalArgumentException("Compensation must be between \$${AppConfig.COMPENSATION_MIN} and \$${AppConfig.COMPENSATION_MAX}")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val compensationStatus = if (amount <= AppConfig.COMPENSATION_AUTO_APPROVE_MAX) {
                CompensationStatus.APPROVED
            } else {
                CompensationStatus.PENDING_APPROVAL
            }

            auditManager.logWithTransaction(
                entityType = "Ticket",
                entityId = ticketId,
                action = AuditAction.COMPENSATION_SUGGEST,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"amount":$amount,"autoApproved":${compensationStatus == CompensationStatus.APPROVED}}"""
            ) {
                ticketQueries.updateTicketCompensation(
                    compensationSuggestedAmount = encryptAmount(amount),
                    compensationApprovedAmount = if (compensationStatus == CompensationStatus.APPROVED) encryptAmount(amount) else null,
                    compensationApprovedBy = if (compensationStatus == CompensationStatus.APPROVED) actorId else null,
                    compensationStatus = compensationStatus.name,
                    updatedAt = now,
                    id = ticketId
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to suggest compensation", e)
            Result.failure(e)
        }
    }

    suspend fun approveCompensation(
        ticketId: String,
        approvedAmount: Double,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ticket = ticketQueries.getTicketById(ticketId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Ticket not found"))

            if (ticket.compensationStatus != CompensationStatus.PENDING_APPROVAL.name) {
                return@withContext Result.failure(
                    IllegalStateException("Compensation is not pending approval")
                )
            }

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Ticket",
                entityId = ticketId,
                action = AuditAction.COMPENSATION_APPROVE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"approvedAmount":$approvedAmount}"""
            ) {
                ticketQueries.updateTicketCompensation(
                    compensationSuggestedAmount = ticket.compensationSuggestedAmount,
                    compensationApprovedAmount = encryptAmount(approvedAmount),
                    compensationApprovedBy = actorId,
                    compensationStatus = CompensationStatus.APPROVED.name,
                    updatedAt = now,
                    id = ticketId
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("TicketRepo", "Failed to approve compensation", e)
            Result.failure(e)
        }
    }

    suspend fun getTicketById(id: String) = withContext(Dispatchers.IO) {
        ticketQueries.getTicketById(id).executeAsOneOrNull()
    }

    suspend fun getTicketsByUserId(userId: String) = withContext(Dispatchers.IO) {
        ticketQueries.getTicketsByUserId(userId).executeAsList()
    }

    suspend fun getTicketsByAgentId(agentId: String) = withContext(Dispatchers.IO) {
        ticketQueries.getTicketsByAgentId(agentId).executeAsList()
    }

    suspend fun getOpenTickets() = withContext(Dispatchers.IO) {
        ticketQueries.getOpenTickets().executeAsList()
    }

    suspend fun getEvidenceByTicketId(ticketId: String) = withContext(Dispatchers.IO) {
        evidenceQueries.getEvidenceByTicketId(ticketId).executeAsList()
    }

    suspend fun pauseSla(ticketId: String, actorId: String, actorRole: Role): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                auditManager.logWithTransaction(
                    entityType = "Ticket", entityId = ticketId,
                    action = AuditAction.UPDATE, actorId = actorId, actorRole = actorRole,
                    details = """{"event":"sla_paused"}"""
                ) {
                    ticketQueries.updateTicketSlaPause(now, now, ticketId)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun resumeSla(ticketId: String, pauseDurationMinutes: Long, actorId: String, actorRole: Role): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                val ticket = ticketQueries.getTicketById(ticketId).executeAsOneOrNull()
                    ?: return@withContext Result.failure(IllegalArgumentException("Ticket not found"))
                val totalPause = ticket.slaTotalPauseDurationMinutes + pauseDurationMinutes

                auditManager.logWithTransaction(
                    entityType = "Ticket", entityId = ticketId,
                    action = AuditAction.UPDATE, actorId = actorId, actorRole = actorRole,
                    details = """{"event":"sla_resumed","totalPauseMinutes":$totalPause}"""
                ) {
                    ticketQueries.updateTicketSlaResume(totalPause, now, ticketId)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun encryptAmount(amount: Double): String =
        encryptionManager.encrypt(amount.toString())

    fun decryptAmount(encrypted: String?): Double? {
        if (encrypted == null) return null
        return try {
            encryptionManager.decrypt(encrypted).toDouble()
        } catch (_: Exception) {
            // Fallback for unencrypted legacy data
            encrypted.toDoubleOrNull()
        }
    }

    /**
     * Calculate deadline adding business hours only (Mon-Fri, 9-18).
     */
    private fun calculateBusinessHoursDeadline(from: LocalDateTime, hours: Int): LocalDateTime {
        var remaining = hours
        var current = from
        val businessDays = AppConfig.SLA_BUSINESS_DAYS.map { DayOfWeek.of(it) }
        val startHour = AppConfig.SLA_BUSINESS_HOUR_START
        val endHour = AppConfig.SLA_BUSINESS_HOUR_END

        while (remaining > 0) {
            if (current.dayOfWeek in businessDays) {
                val availableHour = current.hour
                if (availableHour in startHour until endHour) {
                    val hoursLeftToday = endHour - availableHour
                    if (remaining <= hoursLeftToday) {
                        return current.plusHours(remaining.toLong())
                    }
                    remaining -= hoursLeftToday
                    current = current.withHour(endHour).withMinute(0)
                }
            }
            current = current.plusDays(1).withHour(startHour).withMinute(0)
        }
        return current
    }

    private fun calculateBusinessDaysDeadline(from: LocalDateTime, days: Int): LocalDateTime {
        var remaining = days
        var current = from
        val businessDays = AppConfig.SLA_BUSINESS_DAYS.map { DayOfWeek.of(it) }

        while (remaining > 0) {
            current = current.plusDays(1)
            if (current.dayOfWeek in businessDays) {
                remaining--
            }
        }
        return current.withHour(AppConfig.SLA_BUSINESS_HOUR_END).withMinute(0)
    }
}

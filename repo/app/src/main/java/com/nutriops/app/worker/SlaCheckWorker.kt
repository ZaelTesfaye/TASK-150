package com.nutriops.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.logging.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Background worker for checking SLA compliance on open tickets.
 * Sends warnings and breach notifications.
 * Uses business calendar semantics for SLA calculation.
 */
@HiltWorker
class SlaCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ticketRepository: TicketRepository,
    private val messagingUseCase: ManageMessagingUseCase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = LocalDateTime.now()
            val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val openTickets = ticketRepository.getOpenTickets()

            var warnings = 0
            var breaches = 0

            for (ticket in openTickets) {
                // Check first response SLA
                if (ticket.slaFirstResponseAt == null) {
                    val dueTime = LocalDateTime.parse(ticket.slaFirstResponseDue)
                    val timeUntilDue = java.time.Duration.between(now, dueTime)

                    if (timeUntilDue.isNegative) {
                        // SLA breached
                        breaches++
                        messagingUseCase.sendTriggeredMessage(
                            userId = ticket.userId,
                            triggerEvent = TriggerEvent.SLA_BREACHED,
                            variables = mapOf(
                                "ticketId" to ticket.id,
                                "type" to "first_response",
                                "subject" to ticket.subject
                            )
                        )
                    } else if (timeUntilDue.toHours() <= 1) {
                        // SLA warning (approaching deadline)
                        warnings++
                        messagingUseCase.sendTriggeredMessage(
                            userId = ticket.agentId ?: ticket.userId,
                            triggerEvent = TriggerEvent.SLA_WARNING,
                            variables = mapOf(
                                "ticketId" to ticket.id,
                                "type" to "first_response",
                                "hoursRemaining" to timeUntilDue.toHours().toString()
                            )
                        )
                    }
                }

                // Check resolution SLA
                if (ticket.slaResolvedAt == null) {
                    val dueTime = LocalDateTime.parse(ticket.slaResolutionDue)
                    val adjustedDue = dueTime.plusMinutes(ticket.slaTotalPauseDurationMinutes)
                    val timeUntilDue = java.time.Duration.between(now, adjustedDue)

                    if (timeUntilDue.isNegative) {
                        breaches++
                    }
                }
            }

            AppLogger.info("SlaWorker", "Checked ${openTickets.size} tickets: $warnings warnings, $breaches breaches")
            Result.success()
        } catch (e: Exception) {
            AppLogger.error("SlaWorker", "SLA check failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "sla_check"
    }
}

package com.nutriops.app.audit

import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Immutable, append-only audit trail manager.
 *
 * Every critical write in the system (billing, order, ticket state changes)
 * MUST go through this manager to ensure:
 * 1. Actor identity completeness (who did what)
 * 2. Timestamped traceability (when)
 * 3. State change recording (from -> to)
 * 4. Immutability (no UPDATE/DELETE on AuditEvents table)
 */
class AuditManager(private val database: NutriOpsDatabase) {

    fun log(
        entityType: String,
        entityId: String,
        action: AuditAction,
        actorId: String,
        actorRole: Role,
        details: String = "{}",
        previousState: String? = null,
        newState: String? = null
    ) {
        val id = UUID.randomUUID().toString()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        database.auditEventsQueries.insertAuditEvent(
            id = id,
            entityType = entityType,
            entityId = entityId,
            action = action.name,
            actorId = actorId,
            actorRole = actorRole.name,
            previousState = previousState,
            newState = newState,
            details = details,
            ipAddress = null, // offline app, no IP
            timestamp = timestamp
        )

        AppLogger.audit(
            module = "Audit",
            action = action.name,
            actorId = actorId,
            entityType = entityType,
            entityId = entityId
        )
    }

    fun logWithTransaction(
        entityType: String,
        entityId: String,
        action: AuditAction,
        actorId: String,
        actorRole: Role,
        details: String = "{}",
        previousState: String? = null,
        newState: String? = null,
        transactionBlock: () -> Unit
    ) {
        database.transaction {
            transactionBlock()

            val id = UUID.randomUUID().toString()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            database.auditEventsQueries.insertAuditEvent(
                id = id,
                entityType = entityType,
                entityId = entityId,
                action = action.name,
                actorId = actorId,
                actorRole = actorRole.name,
                previousState = previousState,
                newState = newState,
                details = details,
                ipAddress = null,
                timestamp = timestamp
            )
        }

        AppLogger.audit(
            module = "Audit",
            action = action.name,
            actorId = actorId,
            entityType = entityType,
            entityId = entityId
        )
    }

    fun getAuditTrail(entityType: String, entityId: String) =
        database.auditEventsQueries.getAuditEventsByEntity(entityType, entityId).executeAsList()

    fun getAuditByActor(actorId: String) =
        database.auditEventsQueries.getAuditEventsByActor(actorId).executeAsList()

    fun getAuditByDateRange(startDate: String, endDate: String) =
        database.auditEventsQueries.getAuditEventsByDateRange(startDate, endDate).executeAsList()

    fun getRecentEvents(limit: Long) =
        database.auditEventsQueries.getRecentAuditEvents(limit).executeAsList()
}

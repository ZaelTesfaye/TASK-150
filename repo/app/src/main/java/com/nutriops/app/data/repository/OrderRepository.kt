package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Orders and ChargingSessions with transactional writes,
 * reconciliation state management, and audit trail.
 */
@Singleton
class OrderRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager,
    private val encryptionManager: com.nutriops.app.security.EncryptionManager
) {
    private val queries get() = database.ordersQueries

    suspend fun createOrder(
        userId: String, totalAmount: Double, actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val orderId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val encryptedAmount = encryptionManager.encrypt(totalAmount.toString())
            val encryptedNotes = encryptionManager.encrypt("")

            auditManager.logWithTransaction(
                entityType = "Order", entityId = orderId,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"userId":"$userId","amount":$totalAmount}"""
            ) {
                queries.insertOrder(orderId, userId, "PENDING", encryptedAmount, "UNRECONCILED", actorId, encryptedNotes, now, now)
            }

            Result.success(orderId)
        } catch (e: Exception) {
            AppLogger.error("OrderRepo", "Failed to create order", e)
            Result.failure(e)
        }
    }

    suspend fun updateOrderStatus(
        orderId: String, newStatus: String, actorId: String, actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val order = queries.getOrderById(orderId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Order not found"))

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Order", entityId = orderId,
                action = AuditAction.STATUS_CHANGE, actorId = actorId, actorRole = actorRole,
                previousState = """{"status":"${order.status}"}""",
                newState = """{"status":"$newStatus"}"""
            ) {
                queries.updateOrderStatus(newStatus, actorId, now, orderId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reconcileOrder(
        orderId: String, newState: String, notes: String, actorId: String, actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val order = queries.getOrderById(orderId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Order not found"))

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val encryptedNotes = encryptionManager.encrypt(notes)

            auditManager.logWithTransaction(
                entityType = "Order", entityId = orderId,
                action = AuditAction.UPDATE, actorId = actorId, actorRole = actorRole,
                previousState = """{"reconciliation":"${order.reconciliationState}"}""",
                newState = """{"reconciliation":"$newState"}"""
            ) {
                queries.updateOrderReconciliation(newState, actorId, encryptedNotes, now, orderId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createChargingSession(
        userId: String, orderId: String?, amount: Double, actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sessionId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val encryptedAmount = encryptionManager.encrypt(amount.toString())

            auditManager.logWithTransaction(
                entityType = "ChargingSession", entityId = sessionId,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"userId":"$userId","orderId":${orderId?.let { "\"$it\"" }},"amount":$amount}"""
            ) {
                queries.insertChargingSession(sessionId, userId, orderId, "INITIATED", encryptedAmount, actorId, "{}", now, now)
            }

            Result.success(sessionId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decryptAmount(encrypted: String): Double {
        return try {
            encryptionManager.decrypt(encrypted).toDouble()
        } catch (_: Exception) {
            encrypted.toDoubleOrNull() ?: 0.0
        }
    }

    fun decryptNotes(encrypted: String): String {
        return try {
            encryptionManager.decrypt(encrypted)
        } catch (_: Exception) {
            encrypted
        }
    }

    suspend fun getOrderById(id: String) = withContext(Dispatchers.IO) {
        queries.getOrderById(id).executeAsOneOrNull()
    }

    suspend fun getOrdersByUserId(userId: String) = withContext(Dispatchers.IO) {
        queries.getOrdersByUserId(userId).executeAsList()
    }

    suspend fun getUnreconciledOrders() = withContext(Dispatchers.IO) {
        queries.getUnreconciledOrders().executeAsList()
    }
}

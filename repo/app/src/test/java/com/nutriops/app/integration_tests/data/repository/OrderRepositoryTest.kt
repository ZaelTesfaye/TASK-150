package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.OrderRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.security.testing.JvmEncryptionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class OrderRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var repository: OrderRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        encryptionManager = JvmEncryptionManager()
        repository = OrderRepository(database, auditManager, encryptionManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createOrder ──

    @Test
    fun `create order persists encrypted amount, default statuses, and audit entry`(): Unit = runBlocking {
        val result = repository.createOrder("user1", 99.99, "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
        val orderId = result.getOrNull()!!

        val order = repository.getOrderById(orderId)
        assertThat(order).isNotNull()
        assertThat(order!!.userId).isEqualTo("user1")
        assertThat(order.status).isEqualTo("PENDING")
        assertThat(order.reconciliationState).isEqualTo("UNRECONCILED")
        // Stored value is real AES-GCM ciphertext, not plaintext
        assertThat(order.totalAmount).isNotEqualTo("99.99")
        assertThat(repository.decryptAmount(order.totalAmount)).isEqualTo(99.99)

        val audit = auditManager.getAuditTrail("Order", orderId)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    // ── updateOrderStatus ──

    @Test
    fun `updateOrderStatus transitions status and writes audit`(): Unit = runBlocking {
        val orderId = repository.createOrder("user1", 25.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        repository.updateOrderStatus(orderId, "CONFIRMED", "admin1", Role.ADMINISTRATOR)

        assertThat(repository.getOrderById(orderId)!!.status).isEqualTo("CONFIRMED")
        val audit = auditManager.getAuditTrail("Order", orderId)
            .filter { it.action == AuditAction.STATUS_CHANGE.name }
        assertThat(audit).hasSize(1)
        assertThat(audit.first().newState).contains("CONFIRMED")
    }

    @Test
    fun `updateOrderStatus rejects CHECK-invalid status value at DB layer`(): Unit = runBlocking {
        val orderId = repository.createOrder("user1", 25.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val result = repository.updateOrderStatus(orderId, "WEIRD_STATUS", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isFailure).isTrue()
        // Original order is unchanged
        assertThat(repository.getOrderById(orderId)!!.status).isEqualTo("PENDING")
    }

    @Test
    fun `updateOrderStatus fails for unknown order id`(): Unit = runBlocking {
        val result = repository.updateOrderStatus("missing", "COMPLETED", "admin1", Role.ADMINISTRATOR)
        assertThat(result.isFailure).isTrue()
    }

    // ── reconcileOrder ──

    @Test
    fun `reconcileOrder persists new reconciliation state and encrypted notes`(): Unit = runBlocking {
        val orderId = repository.createOrder("user1", 25.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val result = repository.reconcileOrder(
            orderId, newState = "RECONCILED", notes = "All checks passed",
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
        val stored = repository.getOrderById(orderId)!!
        assertThat(stored.reconciliationState).isEqualTo("RECONCILED")
        // Notes are stored as real AES-GCM ciphertext and round-trip
        assertThat(stored.notes).isNotEqualTo("All checks passed")
        assertThat(repository.decryptNotes(stored.notes)).isEqualTo("All checks passed")
    }

    @Test
    fun `getUnreconciledOrders excludes orders already reconciled`(): Unit = runBlocking {
        val a = repository.createOrder("user1", 10.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        val b = repository.createOrder("user1", 20.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        repository.reconcileOrder(a, "RECONCILED", "ok", "admin1", Role.ADMINISTRATOR)

        val unreconciled = repository.getUnreconciledOrders().map { it.id }
        assertThat(unreconciled).containsExactly(b)
    }

    // ── Query behavior ──

    @Test
    fun `getOrdersByUserId returns only that users orders`(): Unit = runBlocking {
        repository.createOrder("userA", 10.0, "admin1", Role.ADMINISTRATOR)
        repository.createOrder("userA", 20.0, "admin1", Role.ADMINISTRATOR)
        repository.createOrder("userB", 30.0, "admin1", Role.ADMINISTRATOR)

        val a = repository.getOrdersByUserId("userA")
        val b = repository.getOrdersByUserId("userB")

        assertThat(a).hasSize(2)
        assertThat(b).hasSize(1)
        assertThat(a.all { it.userId == "userA" }).isTrue()
    }

    @Test
    fun `fetching orders for a user with none returns empty list, not null or exception`(): Unit = runBlocking {
        val result = repository.getOrdersByUserId("no-orders-user")
        assertThat(result).isEmpty()
    }

    @Test
    fun `getOrderById returns null for unknown id`(): Unit = runBlocking {
        assertThat(repository.getOrderById("missing")).isNull()
    }

    // ── Concurrent inserts ──

    @Test
    fun `concurrent createOrder calls for the same user produce distinct order ids`(): Unit = runBlocking {
        val results = (1..15).map { idx ->
            async { repository.createOrder("user1", idx.toDouble(), "admin1", Role.ADMINISTRATOR) }
        }.awaitAll()

        assertThat(results.all { it.isSuccess }).isTrue()
        val ids = results.map { it.getOrNull()!! }.toSet()
        assertThat(ids).hasSize(15) // all unique

        val orders = repository.getOrdersByUserId("user1")
        assertThat(orders).hasSize(15)
    }

    // ── ChargingSession ──

    @Test
    fun `createChargingSession links back to order when provided`(): Unit = runBlocking {
        val orderId = repository.createOrder("user1", 50.0, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        val sessionId = repository.createChargingSession(
            userId = "user1", orderId = orderId, amount = 50.0,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()!!

        val sessions = database.ordersQueries.getChargingSessionsByOrderId(orderId).executeAsList()
        assertThat(sessions).hasSize(1)
        assertThat(sessions.first().id).isEqualTo(sessionId)
        assertThat(sessions.first().status).isEqualTo("INITIATED")
    }

    // ── Decrypt helpers ──

    @Test
    fun `decryptAmount falls back to plain text for legacy values`() {
        assertThat(repository.decryptAmount("12.5")).isEqualTo(12.5)
    }

    @Test
    fun `decryptNotes falls back to plain text for legacy values`() {
        assertThat(repository.decryptNotes("not encrypted")).isEqualTo("not encrypted")
    }
}

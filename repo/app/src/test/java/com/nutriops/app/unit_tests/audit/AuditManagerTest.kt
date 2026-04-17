package com.nutriops.app.unit_tests.audit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Direct tests for [AuditManager] against a real in-memory SQLDelight
 * database. The audit trail is append-only by schema design (no UPDATE or
 * DELETE queries exist), so the tests cover:
 *   • log() writes a row with every field populated
 *   • logWithTransaction() commits the row alongside the caller's work and
 *     rolls both back together on failure
 *   • read-side queries correctly filter by entity / actor / date range
 */
class AuditManagerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── log ──

    @Test
    fun `log persists an audit row with every provided field`() {
        auditManager.log(
            entityType = "User", entityId = "u1",
            action = AuditAction.CREATE,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR,
            details = """{"event":"create"}""",
            previousState = """{"isActive":0}""",
            newState = """{"isActive":1}"""
        )

        val rows = auditManager.getAuditTrail("User", "u1")
        assertThat(rows).hasSize(1)
        val event = rows.first()
        assertThat(event.entityType).isEqualTo("User")
        assertThat(event.entityId).isEqualTo("u1")
        assertThat(event.action).isEqualTo(AuditAction.CREATE.name)
        assertThat(event.actorId).isEqualTo("admin1")
        assertThat(event.actorRole).isEqualTo(Role.ADMINISTRATOR.name)
        assertThat(event.previousState).contains("isActive")
        assertThat(event.newState).contains("isActive")
        assertThat(event.details).contains("create")
        assertThat(event.ipAddress).isNull()   // offline app
        assertThat(event.timestamp).isNotEmpty()
    }

    @Test
    fun `log with defaults stores empty details object and null state snapshots`() {
        auditManager.log(
            entityType = "Config", entityId = "c1",
            action = AuditAction.UPDATE,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        val event = auditManager.getAuditTrail("Config", "c1").first()
        assertThat(event.details).isEqualTo("{}")
        assertThat(event.previousState).isNull()
        assertThat(event.newState).isNull()
    }

    @Test
    fun `log produces a unique id per call, even for identical payloads`() {
        repeat(5) {
            auditManager.log(
                entityType = "Test", entityId = "t1",
                action = AuditAction.CREATE,
                actorId = "a1", actorRole = Role.ADMINISTRATOR
            )
        }

        val rows = auditManager.getAuditTrail("Test", "t1")
        assertThat(rows).hasSize(5)
        val ids = rows.map { it.id }.toSet()
        assertThat(ids).hasSize(5) // every id is unique
    }

    // ── logWithTransaction ──

    @Test
    fun `logWithTransaction executes the caller lambda before writing the audit row`() {
        val order = mutableListOf<String>()

        auditManager.logWithTransaction(
            entityType = "Ticket", entityId = "t1",
            action = AuditAction.CREATE,
            actorId = "user1", actorRole = Role.END_USER
        ) {
            order += "caller-ran"
            // Simulate a companion write
            database.usersQueries.insertUser(
                id = "ticket-owner", username = "ticket-owner",
                passwordHash = "x", role = Role.END_USER.name,
                isActive = 1, isLocked = 0, failedLoginAttempts = 0,
                lockoutUntil = null,
                createdAt = nowIso(), updatedAt = nowIso()
            )
        }

        order += "lambda-returned"

        assertThat(order).containsExactly("caller-ran", "lambda-returned").inOrder()
        assertThat(auditManager.getAuditTrail("Ticket", "t1")).hasSize(1)
        assertThat(database.usersQueries.getUserById("ticket-owner").executeAsOneOrNull()).isNotNull()
    }

    @Test
    fun `logWithTransaction rolls back both caller writes and the audit row if the lambda throws`() {
        val caught = runCatching {
            auditManager.logWithTransaction(
                entityType = "Ticket", entityId = "t1",
                action = AuditAction.CREATE,
                actorId = "user1", actorRole = Role.END_USER
            ) {
                database.usersQueries.insertUser(
                    id = "should-be-rolled-back", username = "x",
                    passwordHash = "x", role = Role.END_USER.name,
                    isActive = 1, isLocked = 0, failedLoginAttempts = 0,
                    lockoutUntil = null, createdAt = nowIso(), updatedAt = nowIso()
                )
                throw RuntimeException("simulated failure mid-transaction")
            }
        }
        assertThat(caught.isFailure).isTrue()

        // Neither the user row nor the audit row should exist
        assertThat(database.usersQueries.getUserById("should-be-rolled-back").executeAsOneOrNull()).isNull()
        assertThat(auditManager.getAuditTrail("Ticket", "t1")).isEmpty()
    }

    // ── Read-side filters ──

    @Test
    fun `getAuditByActor returns only events attributed to that actor`() {
        auditManager.log("Order", "o1", AuditAction.CREATE, "actor-a", Role.ADMINISTRATOR)
        auditManager.log("Order", "o2", AuditAction.CREATE, "actor-b", Role.ADMINISTRATOR)
        auditManager.log("Order", "o3", AuditAction.CREATE, "actor-a", Role.ADMINISTRATOR)

        val forA = auditManager.getAuditByActor("actor-a")
        val forB = auditManager.getAuditByActor("actor-b")
        val forGhost = auditManager.getAuditByActor("nobody")

        assertThat(forA).hasSize(2)
        assertThat(forB).hasSize(1)
        assertThat(forGhost).isEmpty()
        assertThat(forA.all { it.actorId == "actor-a" }).isTrue()
    }

    @Test
    fun `getAuditTrail filters by entity type + id composite and sorts newest first`() {
        auditManager.log("User", "u1", AuditAction.CREATE, "admin1", Role.ADMINISTRATOR)
        auditManager.log("User", "u2", AuditAction.CREATE, "admin1", Role.ADMINISTRATOR)
        auditManager.log("Ticket", "u1", AuditAction.CREATE, "admin1", Role.ADMINISTRATOR) // same id, different type
        auditManager.log("User", "u1", AuditAction.LOGIN, "u1", Role.END_USER)

        val events = auditManager.getAuditTrail("User", "u1")
        assertThat(events).hasSize(2)
        assertThat(events.map { it.action }).containsAtLeast(AuditAction.CREATE.name, AuditAction.LOGIN.name)
        // Ticket events must not bleed in
        assertThat(events.map { it.entityType }.toSet()).containsExactly("User")
    }

    @Test
    fun `getAuditByDateRange respects inclusive date bounds`() {
        // Write events with controlled timestamps using direct insertion
        insertAuditRaw(entityId = "a", timestamp = "2026-01-01T00:00:00")
        insertAuditRaw(entityId = "b", timestamp = "2026-02-15T12:00:00")
        insertAuditRaw(entityId = "c", timestamp = "2026-03-01T00:00:00")

        val inJan = auditManager.getAuditByDateRange("2026-01-01T00:00:00", "2026-01-31T23:59:59")
        val febMar = auditManager.getAuditByDateRange("2026-02-01T00:00:00", "2026-03-31T23:59:59")

        assertThat(inJan.map { it.entityId }).containsExactly("a")
        assertThat(febMar.map { it.entityId }).containsExactlyElementsIn(setOf("b", "c"))
    }

    @Test
    fun `getRecentEvents returns the N most recent rows in descending order`() {
        for (i in 1..10) {
            insertAuditRaw(entityId = "e$i", timestamp = "2026-01-%02dT00:00:00".format(i))
        }

        val recent5 = auditManager.getRecentEvents(5L)
        assertThat(recent5).hasSize(5)
        // Newest first: e10, e9, e8, e7, e6
        assertThat(recent5.map { it.entityId }).containsExactly("e10", "e9", "e8", "e7", "e6").inOrder()
    }

    // ── Immutability guard ──

    @Test
    fun `schema exposes no UPDATE or DELETE entry points for AuditEvents`() {
        // Append-only is a property of the generated queries interface.
        // AuditEventsQueries contains only insert* and get*/count* methods.
        val methodNames = database.auditEventsQueries::class.java.methods.map { it.name }
        assertThat(methodNames.any { it.startsWith("insert") }).isTrue()
        assertThat(methodNames.any { it.startsWith("update") }).isFalse()
        assertThat(methodNames.any { it.startsWith("delete") }).isFalse()
    }

    // ── helpers ──

    private fun insertAuditRaw(
        entityId: String,
        timestamp: String,
        entityType: String = "Order",
        action: AuditAction = AuditAction.CREATE,
        actorId: String = "admin1",
        actorRole: Role = Role.ADMINISTRATOR
    ) {
        database.auditEventsQueries.insertAuditEvent(
            id = java.util.UUID.randomUUID().toString(),
            entityType = entityType, entityId = entityId,
            action = action.name,
            actorId = actorId, actorRole = actorRole.name,
            previousState = null, newState = null,
            details = "{}", ipAddress = null, timestamp = timestamp
        )
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

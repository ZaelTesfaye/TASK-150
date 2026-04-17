package com.nutriops.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Unit tests for [DatabaseFactory] and its produced [NutriOpsDatabase].
 *
 * The production implementation of [DatabaseFactory.create] constructs an
 * encrypted, SQLCipher-backed [app.cash.sqldelight.driver.android.AndroidSqliteDriver]
 * which requires Android native libraries. On the host JVM we exercise the
 * equivalent in-memory path via SQLDelight's [JdbcSqliteDriver] -- the same
 * approach Room's `inMemoryDatabaseBuilder` uses for JVM tests, adapted to
 * SQLDelight.
 *
 * Covers:
 *   - Schema initialization succeeds without exception
 *   - Every query object exposed by [NutriOpsDatabase] is non-null
 *   - Trivial DAO queries run correctly against the live in-memory DB
 */
class DatabaseFactoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Factory contract ──

    @Test
    fun `DatabaseFactory create is declared with expected signature`() {
        val method = DatabaseFactory::class.java.declaredMethods.firstOrNull { it.name == "create" }
        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(NutriOpsDatabase::class.java)
        assertThat(method.parameterTypes.toList()).containsExactly(
            android.content.Context::class.java, String::class.java
        ).inOrder()
    }

    // ── Schema initialization ──

    @Test
    fun `schema creation produces a valid NutriOpsDatabase instance`() {
        assertThat(database).isNotNull()
    }

    @Test
    fun `all declared query objects on NutriOpsDatabase are non-null`() {
        // If the generated schema is incomplete any of the query bindings
        // would come back null. The assertion below is the broad smoke test
        // equivalent to calling getters on every DAO in a Room database.
        assertThat(database.auditEventsQueries).isNotNull()
        assertThat(database.usersQueries).isNotNull()
        assertThat(database.profilesQueries).isNotNull()
        assertThat(database.mealPlansQueries).isNotNull()
        assertThat(database.mealsQueries).isNotNull()
        assertThat(database.swapMappingsQueries).isNotNull()
        assertThat(database.learningPlansQueries).isNotNull()
        assertThat(database.ticketsQueries).isNotNull()
        assertThat(database.evidenceItemsQueries).isNotNull()
        assertThat(database.messagesQueries).isNotNull()
        assertThat(database.configsQueries).isNotNull()
        assertThat(database.rulesQueries).isNotNull()
        assertThat(database.metricsSnapshotsQueries).isNotNull()
        assertThat(database.rolloutsQueries).isNotNull()
        assertThat(database.ordersQueries).isNotNull()
    }

    // ── Trivial DAO queries work after schema init ──

    @Test
    fun `Users table is queryable after schema init`() {
        assertThat(database.usersQueries.countUsers().executeAsOne()).isEqualTo(0L)
    }

    @Test
    fun `AuditEvents table is queryable after schema init`() {
        assertThat(database.auditEventsQueries.countAuditEvents().executeAsOne()).isEqualTo(0L)
        assertThat(database.auditEventsQueries.getRecentAuditEvents(10L).executeAsList()).isEmpty()
    }

    @Test
    fun `Users CRUD round-trips through an insert and getUserById`() {
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        database.usersQueries.insertUser(
            id = "u1", username = "alice", passwordHash = "x",
            role = "END_USER", isActive = 1L, isLocked = 0L,
            failedLoginAttempts = 0L, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )

        val stored = database.usersQueries.getUserById("u1").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.username).isEqualTo("alice")
        assertThat(database.usersQueries.countUsers().executeAsOne()).isEqualTo(1L)
    }

    @Test
    fun `schema is transactional - rollback reverts inserted rows`() {
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        runCatching {
            database.transaction {
                database.usersQueries.insertUser(
                    id = "u1", username = "alice", passwordHash = "x",
                    role = "END_USER", isActive = 1L, isLocked = 0L,
                    failedLoginAttempts = 0L, lockoutUntil = null,
                    createdAt = now, updatedAt = now
                )
                throw RuntimeException("simulated failure")
            }
        }
        // The insert must have been rolled back
        assertThat(database.usersQueries.countUsers().executeAsOne()).isEqualTo(0L)
    }
}

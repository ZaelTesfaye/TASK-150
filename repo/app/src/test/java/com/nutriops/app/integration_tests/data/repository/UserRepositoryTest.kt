package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.PasswordHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class UserRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = UserRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createUser ──

    @Test
    fun `create and retrieve by id returns equal data with correct role`() = runBlocking {
        val result = repository.createUser(
            "alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()
        val userId = result.getOrNull()!!

        val stored = repository.getUserById(userId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.username).isEqualTo("alice")
        assertThat(stored.role).isEqualTo(Role.END_USER.name)
        assertThat(stored.isActive).isEqualTo(1L)
        assertThat(stored.isLocked).isEqualTo(0L)
        // Password must be hashed, not stored plaintext
        assertThat(stored.passwordHash).isNotEqualTo("password123")
        assertThat(PasswordHasher.verify("password123", stored.passwordHash)).isTrue()

        val audit = auditManager.getAuditTrail("User", userId)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    @Test
    fun `creating two users with the same username fails with a uniqueness error`() = runBlocking {
        val first = repository.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        val second = repository.createUser("alice", "other123", Role.AGENT, "admin1", Role.ADMINISTRATOR)

        assertThat(first.isSuccess).isTrue()
        assertThat(second.isFailure).isTrue()
        assertThat(second.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(second.exceptionOrNull()?.message).contains("already exists")
    }

    @Test
    fun `case-sensitive username lookup follows SQLite default semantics`() = runBlocking {
        repository.createUser("Alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        assertThat(repository.getUserByUsername("Alice")).isNotNull()
        // SQLite BINARY (default) collation is case-sensitive — "alice" must not match "Alice"
        assertThat(repository.getUserByUsername("alice")).isNull()
    }

    @Test
    fun `creating a user with invalid role CHECK constraint fails`() = runBlocking {
        // Bypass the repository (which only accepts Role enum) and insert
        // directly with a bogus role string to exercise the CHECK constraint.
        val result = runCatching {
            database.usersQueries.insertUser(
                id = "u1", username = "bob", passwordHash = "x",
                role = "NOT_A_ROLE", isActive = 1, isLocked = 0,
                failedLoginAttempts = 0, lockoutUntil = null,
                createdAt = "2026-01-01T00:00:00",
                updatedAt = "2026-01-01T00:00:00"
            )
        }
        assertThat(result.isFailure).isTrue()
    }

    // ── getUsersByRole ──

    @Test
    fun `getUsersByRole filters correctly`() = runBlocking {
        repository.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        repository.createUser("bob", "password123", Role.AGENT, "admin1", Role.ADMINISTRATOR)
        repository.createUser("carol", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        val agents = repository.getUsersByRole(Role.AGENT)
        val endUsers = repository.getUsersByRole(Role.END_USER)

        assertThat(agents.map { it.username }).containsExactly("bob")
        assertThat(endUsers.map { it.username }).containsExactly("alice", "carol")
    }

    @Test
    fun `getAllUsers returns every row in reverse-chronological order`() = runBlocking {
        repository.createUser("first", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        Thread.sleep(10)
        repository.createUser("second", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        val all = repository.getAllUsers()
        assertThat(all).hasSize(2)
        assertThat(all.first().username).isEqualTo("second")
    }

    @Test
    fun `countUsers reflects total row count`() = runBlocking {
        assertThat(repository.countUsers()).isEqualTo(0L)

        repository.createUser("a", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        repository.createUser("b", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        assertThat(repository.countUsers()).isEqualTo(2L)
    }

    // ── deactivateUser (soft-delete semantics) ──

    @Test
    fun `deactivateUser marks isActive = 0 and persists it`() = runBlocking {
        val userId = repository.createUser(
            "alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR
        ).getOrNull()!!

        val res = repository.deactivateUser(userId, "admin1", Role.ADMINISTRATOR)

        assertThat(res.isSuccess).isTrue()
        val stored = repository.getUserById(userId)!!
        assertThat(stored.isActive).isEqualTo(0L)

        val audit = auditManager.getAuditTrail("User", userId)
            .filter { it.action == AuditAction.UPDATE.name }
        assertThat(audit).hasSize(1)
        assertThat(audit.first().details).contains("deactivate")
    }

    @Test
    fun `deactivateUser fails for unknown user id`() = runBlocking {
        val res = repository.deactivateUser("missing", "admin1", Role.ADMINISTRATOR)
        assertThat(res.isFailure).isTrue()
    }

    @Test
    fun `deactivated users still appear in getAllUsers - this is soft delete`() = runBlocking {
        val userId = repository.createUser(
            "alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR
        ).getOrNull()!!

        repository.deactivateUser(userId, "admin1", Role.ADMINISTRATOR)

        val all = repository.getAllUsers()
        assertThat(all.map { it.username }).contains("alice")
        // Filtering active-only is the caller's responsibility
        assertThat(all.filter { it.isActive == 1L }).isEmpty()
    }
}

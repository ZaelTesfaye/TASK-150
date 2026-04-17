package com.nutriops.app.integration_tests.domain.usecase.auth

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.security.PasswordHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration counterpart to the mock-based `ManageUsersUseCaseTest`. Wires
 * a real [UserRepository] + [AuditManager] against an in-memory SQLDelight
 * database. Every result is driven by real persistence.
 */
class ManageUsersUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: UserRepository
    private lateinit var useCase: ManageUsersUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = UserRepository(database, auditManager)
        useCase = ManageUsersUseCase(repository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `admin creates a user and the password is stored hashed in the real DB`() = runBlocking {
        val result = useCase.createUser(
            "alice", "AlicePass1!", Role.END_USER, "admin1", Role.ADMINISTRATOR
        )
        assertThat(result.isSuccess).isTrue()
        val userId = result.getOrNull()!!

        val stored = database.usersQueries.getUserById(userId).executeAsOne()
        assertThat(stored.username).isEqualTo("alice")
        assertThat(stored.role).isEqualTo(Role.END_USER.name)
        assertThat(stored.passwordHash).isNotEqualTo("AlicePass1!")
        assertThat(PasswordHasher.verify("AlicePass1!", stored.passwordHash)).isTrue()
    }

    @Test
    fun `non-admin actors are denied and no user row is created`() = runBlocking {
        val result = useCase.createUser(
            "alice", "AlicePass1!", Role.END_USER, "agent1", Role.AGENT
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(database.usersQueries.getUserByUsername("alice").executeAsOneOrNull()).isNull()
    }

    @Test
    fun `getAllUsers reflects every real row in the DB when called by admin`() = runBlocking {
        useCase.createUser("a", "Password1!", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        useCase.createUser("b", "Password1!", Role.AGENT, "admin1", Role.ADMINISTRATOR)

        val result = useCase.getAllUsers(Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.map { it.username }).containsExactly("a", "b")
    }

    @Test
    fun `deactivateUser flips isActive in the real DB and writes an UPDATE audit row`() = runBlocking {
        val userId = useCase.createUser(
            "alice", "Password1!", Role.END_USER, "admin1", Role.ADMINISTRATOR
        ).getOrNull()!!

        val result = useCase.deactivateUser(userId, "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()

        val stored = database.usersQueries.getUserById(userId).executeAsOne()
        assertThat(stored.isActive).isEqualTo(0L)

        val audit = auditManager.getAuditTrail("User", userId)
            .filter { it.action == AuditAction.UPDATE.name }
        assertThat(audit).isNotEmpty()
    }

    @Test
    fun `duplicate username insertion is rejected by the repository (unique constraint)`() = runBlocking {
        useCase.createUser("alice", "P1!abcdef", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        val second = useCase.createUser(
            "alice", "P2!xyz123", Role.AGENT, "admin1", Role.ADMINISTRATOR
        )
        assertThat(second.isFailure).isTrue()
    }
}

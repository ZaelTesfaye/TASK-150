package com.nutriops.app.integration_tests.domain.usecase.auth

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration counterpart to [com.nutriops.app.unit_tests.domain.usecase.auth.LoginUseCaseTest].
 * Wires a real [AuthManager] backed by an in-memory SQLDelight database. No mocks --
 * every result type produced by the use case is the outcome of a real
 * DB round-trip.
 */
class LoginUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var useCase: LoginUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        useCase = LoginUseCase(authManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `needsBootstrap is true on an empty DB and false once an admin exists`() {
        assertThat(useCase.needsBootstrap()).isTrue()
        useCase.bootstrap("admin", "AdminPass1!")
        assertThat(useCase.needsBootstrap()).isFalse()
    }

    @Test
    fun `bootstrap persists a real admin user and login succeeds with those credentials`() {
        val bootstrapResult = useCase.bootstrap("admin", "AdminPass1!")
        assertThat(bootstrapResult).isInstanceOf(AuthManager.AuthResult.Success::class.java)

        // Persisted user row has the expected role
        val stored = database.usersQueries.getUserByUsername("admin").executeAsOne()
        assertThat(stored.role).isEqualTo(Role.ADMINISTRATOR.name)

        useCase.logout()
        assertThat(useCase.isAuthenticated()).isFalse()

        val loginResult = useCase.login("admin", "AdminPass1!")
        assertThat(loginResult).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        assertThat(useCase.isAuthenticated()).isTrue()
    }

    @Test
    fun `register persists an END_USER account and subsequent login works`() {
        useCase.bootstrap("root", "AdminPass1!")
        useCase.logout()

        val registerResult = useCase.register("alice", "AlicePass1!")
        assertThat(registerResult).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        val stored = database.usersQueries.getUserByUsername("alice").executeAsOne()
        assertThat(stored.role).isEqualTo(Role.END_USER.name)

        useCase.logout()
        val loginResult = useCase.login("alice", "AlicePass1!")
        assertThat(loginResult).isInstanceOf(AuthManager.AuthResult.Success::class.java)
    }

    @Test
    fun `login with wrong password returns Failure and increments failed counter`() {
        useCase.bootstrap("admin", "AdminPass1!")
        useCase.logout()

        val result = useCase.login("admin", "wrong-password")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)

        val stored = database.usersQueries.getUserByUsername("admin").executeAsOne()
        assertThat(stored.failedLoginAttempts).isEqualTo(1L)
    }

    @Test
    fun `repeated wrong passwords lock the account and return AccountLocked`() {
        useCase.bootstrap("admin", "AdminPass1!")
        useCase.logout()

        var finalResult: AuthManager.AuthResult? = null
        repeat(5) { finalResult = useCase.login("admin", "wrong-$it") }

        assertThat(finalResult).isInstanceOf(AuthManager.AuthResult.AccountLocked::class.java)

        val stored = database.usersQueries.getUserByUsername("admin").executeAsOne()
        assertThat(stored.isLocked).isEqualTo(1L)
    }

    @Test
    fun `logout clears the AuthManager session observable on the real state flow`() {
        useCase.bootstrap("admin", "AdminPass1!")
        assertThat(useCase.isAuthenticated()).isTrue()

        useCase.logout()
        assertThat(useCase.isAuthenticated()).isFalse()
        assertThat(authManager.currentSession.value).isNull()
    }
}

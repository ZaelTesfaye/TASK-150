package com.nutriops.app.unit_tests.security

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.AuthManager
import org.junit.After
import org.junit.Before
import org.junit.Test

class AuthManagerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
        AppConfig.clearTestOverrides()
    }

    // ── bootstrap ──

    @Test
    fun `needsBootstrap returns true when no admin exists`() {
        assertThat(authManager.needsBootstrap()).isTrue()
    }

    @Test
    fun `bootstrapAdmin on first run creates admin and sets session`() {
        val result = authManager.bootstrapAdmin("admin", "adminpass1")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        val session = (result as AuthManager.AuthResult.Success).session
        assertThat(session.username).isEqualTo("admin")
        assertThat(session.role).isEqualTo(Role.ADMINISTRATOR)
        assertThat(authManager.isAuthenticated).isTrue()
        assertThat(authManager.currentSession.value).isEqualTo(session)
    }

    @Test
    fun `bootstrapAdmin after admin exists returns failure`() {
        authManager.bootstrapAdmin("admin", "adminpass1")
        authManager.logout()

        val result = authManager.bootstrapAdmin("admin2", "adminpass2")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat((result as AuthManager.AuthResult.Failure).message).contains("already exists")
    }

    @Test
    fun `bootstrapAdmin rejects blank username`() {
        val result = authManager.bootstrapAdmin("", "adminpass1")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
    }

    @Test
    fun `bootstrapAdmin rejects short password`() {
        val result = authManager.bootstrapAdmin("admin", "short")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
    }

    @Test
    fun `bootstrapAdmin writes audit event`() {
        val result = authManager.bootstrapAdmin("admin", "adminpass1") as AuthManager.AuthResult.Success
        val events = auditManager.getAuditTrail("User", result.session.userId)
        assertThat(events).isNotEmpty()
        assertThat(events.first().action).isEqualTo("CREATE")
    }

    // ── register (end-user self-registration) ──

    @Test
    fun `register creates END_USER account and logs in`() {
        val result = authManager.register("alice", "password123")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        val session = (result as AuthManager.AuthResult.Success).session
        assertThat(session.username).isEqualTo("alice")
        assertThat(session.role).isEqualTo(Role.END_USER)
        assertThat(authManager.isAuthenticated).isTrue()
    }

    @Test
    fun `register rejects duplicate username`() {
        authManager.register("alice", "password123")
        authManager.logout()

        val result = authManager.register("alice", "otherpass123")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat((result as AuthManager.AuthResult.Failure).message).contains("already taken")
    }

    @Test
    fun `register rejects short password`() {
        val result = authManager.register("alice", "short")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat(authManager.isAuthenticated).isFalse()
    }

    @Test
    fun `register rejects blank username`() {
        val result = authManager.register("", "password123")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
    }

    // ── login ──

    @Test
    fun `login succeeds with correct credentials`() {
        authManager.register("alice", "password123")
        authManager.logout()

        val result = authManager.login("alice", "password123")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        assertThat(authManager.isAuthenticated).isTrue()
        assertThat(authManager.currentRole).isEqualTo(Role.END_USER)
    }

    @Test
    fun `login fails with wrong password`() {
        authManager.register("alice", "password123")
        authManager.logout()

        val result = authManager.login("alice", "wrongPassword")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat(authManager.isAuthenticated).isFalse()
    }

    @Test
    fun `login fails for unknown user`() {
        val result = authManager.login("ghost", "whatever")
        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat((result as AuthManager.AuthResult.Failure).message).contains("Invalid credentials")
    }

    @Test
    fun `login locks account after MAX_LOGIN_ATTEMPTS wrong passwords`() {
        authManager.register("alice", "password123")
        authManager.logout()

        val wrongAttempts = AppConfig.MAX_LOGIN_ATTEMPTS
        var lastResult: AuthManager.AuthResult? = null
        repeat(wrongAttempts) {
            lastResult = authManager.login("alice", "wrongPassword")
        }

        assertThat(lastResult).isInstanceOf(AuthManager.AuthResult.AccountLocked::class.java)
        // Subsequent login with correct password should still report locked
        val afterLock = authManager.login("alice", "password123")
        assertThat(afterLock).isInstanceOf(AuthManager.AuthResult.AccountLocked::class.java)
    }

    @Test
    fun `successful login resets failed attempts`() {
        authManager.register("alice", "password123")
        authManager.logout()

        // A few failed attempts (not enough to lock)
        authManager.login("alice", "wrong1")
        authManager.login("alice", "wrong2")
        val success = authManager.login("alice", "password123")
        assertThat(success).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        authManager.logout()

        // The counter should be reset — a fresh round of wrong attempts up to
        // MAX_LOGIN_ATTEMPTS - 1 must still not lock the account.
        repeat(AppConfig.MAX_LOGIN_ATTEMPTS - 1) {
            authManager.login("alice", "wrong")
        }
        val stillOpen = authManager.login("alice", "password123")
        assertThat(stillOpen).isInstanceOf(AuthManager.AuthResult.Success::class.java)
    }

    @Test
    fun `login writes LOGIN audit event on success`() {
        val registered = authManager.register("alice", "password123") as AuthManager.AuthResult.Success
        val userId = registered.session.userId
        authManager.logout()

        authManager.login("alice", "password123")

        val events = auditManager.getAuditTrail("User", userId)
        assertThat(events.map { it.action }).contains("LOGIN")
    }

    @Test
    fun `logout clears current session`() {
        authManager.register("alice", "password123")
        assertThat(authManager.isAuthenticated).isTrue()

        authManager.logout()

        assertThat(authManager.isAuthenticated).isFalse()
        assertThat(authManager.currentSession.value).isNull()
    }

    @Test
    fun `requireRole returns true only for current session role`() {
        authManager.register("alice", "password123")

        assertThat(authManager.requireRole(Role.END_USER)).isTrue()
        assertThat(authManager.requireRole(Role.ADMINISTRATOR)).isFalse()
        assertThat(authManager.requireRole(Role.ADMINISTRATOR, Role.END_USER)).isTrue()
    }
}

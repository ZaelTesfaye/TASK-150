package com.nutriops.app.ui.auth

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration counterpart to [AuthViewModelTest]. Wires a real
 * [LoginUseCase] + [AuthManager] backed by an in-memory SQLDelight database.
 * No mocks -- login state transitions are driven by real persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var loginUseCase: LoginUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        loginUseCase = LoginUseCase(authManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun `initial state reflects needsBootstrap true on an empty database`() {
        val vm = AuthViewModel(loginUseCase, authManager)
        assertThat(vm.uiState.value.needsBootstrap).isTrue()
        assertThat(vm.uiState.value.isAuthenticated).isFalse()
    }

    @Test
    fun `bootstrap() creates an admin in the DB and transitions state to authenticated`() = runTest {
        val vm = AuthViewModel(loginUseCase, authManager)

        vm.bootstrap("admin", "AdminPass1!")
        advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.role).isEqualTo(Role.ADMINISTRATOR.name)
        // Real user row exists in the DB
        val stored = database.usersQueries.getUserByUsername("admin").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.role).isEqualTo(Role.ADMINISTRATOR.name)
    }

    @Test
    fun `register() persists an END_USER and transitions to authenticated`() = runTest {
        // Seed an admin so needsBootstrap doesn't redirect in a real app flow
        authManager.bootstrapAdmin("root", "AdminPass1!")
        authManager.logout()

        val vm = AuthViewModel(loginUseCase, authManager)
        vm.register("alice", "AlicePass1!")
        advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.role).isEqualTo(Role.END_USER.name)
        val stored = database.usersQueries.getUserByUsername("alice").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.role).isEqualTo(Role.END_USER.name)
    }

    @Test
    fun `login() with wrong password surfaces invalid credentials error`() = runTest {
        authManager.bootstrapAdmin("admin", "AdminPass1!")
        authManager.logout()

        val vm = AuthViewModel(loginUseCase, authManager)
        vm.login("admin", "totally-wrong")
        advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isFalse()
        assertThat(vm.uiState.value.error).isEqualTo("Invalid credentials")
    }

    @Test
    fun `login() followed by logout() returns state to unauthenticated`() = runTest {
        authManager.bootstrapAdmin("admin", "AdminPass1!")
        authManager.logout()

        val vm = AuthViewModel(loginUseCase, authManager)
        vm.login("admin", "AdminPass1!")
        advanceUntilIdle()
        assertThat(vm.uiState.value.isAuthenticated).isTrue()

        vm.logout()
        assertThat(vm.uiState.value.isAuthenticated).isFalse()
    }

    @Test
    fun `failed logins accumulate and eventually lock the account`() = runTest {
        authManager.bootstrapAdmin("admin", "AdminPass1!")
        authManager.logout()

        val vm = AuthViewModel(loginUseCase, authManager)
        // MAX_LOGIN_ATTEMPTS defaults to 5
        repeat(5) {
            vm.login("admin", "wrong-$it")
            advanceUntilIdle()
        }

        // Subsequent attempts must surface the "locked" error message
        vm.login("admin", "AdminPass1!")
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).contains("locked")
    }

    // ── State-flow transition observation (loading -> success | error) ──

    @Test
    fun `login emits loading true then success snapshot backed by a real DB row`() = runTest {
        authManager.bootstrapAdmin("admin", "AdminPass1!")
        authManager.logout()

        val stepDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stepDispatcher)

        val vm = AuthViewModel(loginUseCase, authManager)
        val emissions = mutableListOf<AuthUiState>()
        val collectorScope = CoroutineScope(stepDispatcher)
        val job = collectorScope.launch { vm.uiState.collect { emissions += it } }

        vm.login("admin", "AdminPass1!")

        // First phase: isLoading == true, error still null, not authenticated yet
        stepDispatcher.scheduler.runCurrent()
        assertThat(emissions.any { it.isLoading }).isTrue()

        stepDispatcher.scheduler.advanceUntilIdle()
        // Terminal snapshot: authenticated, loading cleared, error null
        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.error).isNull()
        assertThat(vm.uiState.value.role).isEqualTo(Role.ADMINISTRATOR.name)

        // Underlying DB confirms the login attempt went through the real
        // AuthManager path and ended in a successful audit row
        val loginAudit = auditManager.getAuditByActor(authManager.currentUserId)
            .filter { it.action == "LOGIN" }
        assertThat(loginAudit).isNotEmpty()

        job.cancel()
    }

    @Test
    fun `login emits loading true then error snapshot backed by a real DB row`() = runTest {
        authManager.bootstrapAdmin("admin", "AdminPass1!")
        authManager.logout()

        val stepDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stepDispatcher)

        val vm = AuthViewModel(loginUseCase, authManager)
        val emissions = mutableListOf<AuthUiState>()
        val collectorScope = CoroutineScope(stepDispatcher)
        val job = collectorScope.launch { vm.uiState.collect { emissions += it } }

        vm.login("admin", "wrong-password")

        stepDispatcher.scheduler.runCurrent()
        assertThat(emissions.any { it.isLoading }).isTrue()

        stepDispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.uiState.value.isAuthenticated).isFalse()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.error).isEqualTo("Invalid credentials")

        // The real AuthManager wrote a LOGIN_FAILED audit row and incremented
        // the failedLoginAttempts counter -- both are observable side effects
        // of the integrated path.
        val stored = database.usersQueries.getUserByUsername("admin").executeAsOne()
        assertThat(stored.failedLoginAttempts).isEqualTo(1L)
        val failedAudit = auditManager.getAuditByActor(stored.id)
            .filter { it.action == "LOGIN_FAILED" }
        assertThat(failedAudit).hasSize(1)

        job.cancel()
    }
}

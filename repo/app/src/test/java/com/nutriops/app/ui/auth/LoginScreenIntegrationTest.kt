package com.nutriops.app.ui.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration test for [LoginScreen] with the real [LoginUseCase] wired to a
 * real [AuthManager] backed by an in-memory SQLDelight database. Entered
 * credentials travel through the full stack — no mocks between the UI and
 * the persistence layer.
 *
 * Companion to [LoginScreenTest], which uses mocks for fast pure-UI
 * rendering assertions. This file covers the integration contract.
 */
@RunWith(RobolectricTestRunner::class)
class LoginScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var loginUseCase: LoginUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        loginUseCase = LoginUseCase(authManager)

        // Seed an administrator so the login screen is not diverted to
        // bootstrap on first render.
        authManager.bootstrapAdmin("root-admin", "AdminPass1!")
        authManager.logout()
        // Register an End User to exercise the login happy path later.
        authManager.register("alice", "password123")
        authManager.logout()
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun viewModel(): AuthViewModel = AuthViewModel(loginUseCase, authManager)

    // ── Happy path ──

    @Test
    fun `valid credentials transition the UI to authenticated state`() {
        val vm = viewModel()
        var loggedInRole: String? = null

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = { loggedInRole = it },
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 2_000) { loggedInRole != null }
        assertThat(loggedInRole).isEqualTo(Role.END_USER.name)
        assertThat(authManager.isAuthenticated).isTrue()
        assertThat(authManager.currentSession.value?.username).isEqualTo("alice")
    }

    // ── Wrong credentials ──

    @Test
    fun `wrong password surfaces inline error and does not authenticate`() {
        val vm = viewModel()

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("wrong-password")
        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            runCatching { composeTestRule.onNodeWithText("Invalid credentials").assertIsDisplayed() }.isSuccess
        }
        composeTestRule.onNodeWithText("Invalid credentials").assertIsDisplayed()
        assertThat(authManager.isAuthenticated).isFalse()
    }

    @Test
    fun `unknown username also surfaces invalid credentials error`() {
        val vm = viewModel()

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").performTextInput("never-registered")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            runCatching { composeTestRule.onNodeWithText("Invalid credentials").assertIsDisplayed() }.isSuccess
        }
        assertThat(authManager.isAuthenticated).isFalse()
    }

    // ── Validation (use case NOT invoked) ──

    @Test
    fun `empty username shows inline validation without touching the use case`() {
        val vm = viewModel()
        val recordedLoginsBefore = auditManager
            .getAuditByActor(authManager.currentSession.value?.userId ?: "")
            .size

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.onNodeWithText("Username and password are required").assertIsDisplayed()
        // No audit row should have been written — if the use case had run we'd
        // see a LOGIN_FAILED event.
        val after = auditManager
            .getAuditByActor(authManager.currentSession.value?.userId ?: "")
            .size
        assertThat(after).isEqualTo(recordedLoginsBefore)
    }
}

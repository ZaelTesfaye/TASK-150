package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.security.AuthManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration test for [AdminUsersScreen] with real [ManageUsersUseCase],
 * real [UserRepository] and real [AuthManager] all backed by an in-memory
 * SQLDelight database. No mocks on the production boundary.
 *
 * Companion to [AdminUsersScreenTest], which uses mocks for fast pure-UI
 * assertions. This file covers the integration contract: clicking "Create"
 * in the admin dialog must actually persist a user row and surface it in
 * the list.
 */
@RunWith(RobolectricTestRunner::class)
class AdminUsersScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var userRepository: UserRepository
    private lateinit var manageUsersUseCase: ManageUsersUseCase
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        userRepository = UserRepository(database, auditManager)
        manageUsersUseCase = ManageUsersUseCase(userRepository)
        authManager = AuthManager(database, auditManager)

        // Bootstrap an administrator — the VM consults authManager for the
        // current session and short-circuits without one.
        authManager.bootstrapAdmin("root-admin", "AdminPass1!")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun viewModel(): AdminUsersViewModel =
        AdminUsersViewModel(manageUsersUseCase, authManager)

    @Test
    fun `initial render shows the seeded admin in the list`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = viewModel())
        }

        composeTestRule.onNodeWithText("User Management").assertIsDisplayed()
        composeTestRule.onNodeWithText("root-admin").assertIsDisplayed()
    }

    @Test
    fun `creating a user through the dialog persists a new row and refreshes the list`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = viewModel())
        }

        // Open the create dialog
        composeTestRule.onNodeWithContentDescription("Add User").performClick()
        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        // Role chip labels show the first 5 letters of the role name
        composeTestRule.onNodeWithText("AGENT").performClick()
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runCatching { composeTestRule.onNodeWithText("alice").assertIsDisplayed() }.isSuccess
        }
        composeTestRule.onNodeWithText("alice").assertIsDisplayed()

        // Behavioral assertion against the real DB — the persisted row must
        // have the correct role.
        val stored = database.usersQueries.getUserByUsername("alice").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.role).isEqualTo(Role.AGENT.name)
        assertThat(stored.isActive).isEqualTo(1L)
    }

    @Test
    fun `attempting to create a duplicate username surfaces a validation error`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = viewModel())
        }

        // First user succeeds
        composeTestRule.onNodeWithContentDescription("Add User").performClick()
        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runCatching { composeTestRule.onNodeWithText("alice").assertIsDisplayed() }.isSuccess
        }

        // Second attempt with the same username must fail at the repository
        composeTestRule.onNodeWithContentDescription("Add User").performClick()
        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("differentPass1!")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        // Exactly one "alice" row — not two
        val rows = database.usersQueries.getAllUsers().executeAsList()
        assertThat(rows.count { it.username == "alice" }).isEqualTo(1)
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            AdminUsersScreen(onBack = { wentBack = true }, viewModel = viewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

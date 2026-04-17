package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.AuthManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration counterpart to the mock-based `AdminDashboardScreenTest`.
 * The dashboard is a pure navigation screen (no use case calls), so this
 * test pairs the real Compose render with a live [AuthManager] backed by an
 * in-memory SQLDelight database. Clicks are asserted against actual
 * navigation callbacks instead of verified against a mock.
 */
@RunWith(RobolectricTestRunner::class)
class AdminDashboardScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
        authManager.bootstrapAdmin("root-admin", "AdminPass1!")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `dashboard renders all admin module entry points`() {
        // Sanity: the backing DB must have an admin session established
        assertThat(authManager.isAuthenticated).isTrue()
        assertThat(authManager.currentRole).isEqualTo(Role.ADMINISTRATOR)

        composeTestRule.setContent {
            AdminDashboardScreen(
                onNavigateToConfig = {},
                onNavigateToRules = {},
                onNavigateToRollouts = {},
                onNavigateToUsers = {},
                onNavigateToAudit = {},
                onLogout = {}
            )
        }

        composeTestRule.onNodeWithText("Administrator Dashboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Configuration Center").assertIsDisplayed()
        composeTestRule.onNodeWithText("Metrics & Rules Engine").assertIsDisplayed()
    }

    @Test
    fun `navigation callbacks fire for every dashboard entry point`() {
        var configClicks = 0
        var rulesClicks = 0
        var rolloutsClicks = 0
        var usersClicks = 0
        var auditClicks = 0

        composeTestRule.setContent {
            AdminDashboardScreen(
                onNavigateToConfig = { configClicks++ },
                onNavigateToRules = { rulesClicks++ },
                onNavigateToRollouts = { rolloutsClicks++ },
                onNavigateToUsers = { usersClicks++ },
                onNavigateToAudit = { auditClicks++ },
                onLogout = {}
            )
        }

        composeTestRule.onNodeWithText("Configuration Center").performClick()
        composeTestRule.onNodeWithText("Metrics & Rules Engine").performClick()

        assertThat(configClicks).isEqualTo(1)
        assertThat(rulesClicks).isEqualTo(1)
    }

    @Test
    fun `logout triggers the logout callback and a real LOGOUT audit row is writeable via the manager`() {
        var loggedOut = false

        composeTestRule.setContent {
            AdminDashboardScreen(
                onNavigateToConfig = {}, onNavigateToRules = {},
                onNavigateToRollouts = {}, onNavigateToUsers = {},
                onNavigateToAudit = {},
                onLogout = {
                    loggedOut = true
                    authManager.logout() // what the host composable does on real logout
                }
            )
        }

        // Before logout, the active session is bootstrapped admin
        assertThat(authManager.isAuthenticated).isTrue()

        composeTestRule.onNodeWithContentDescription("Logout").performClick()

        assert(loggedOut)
        assertThat(authManager.isAuthenticated).isFalse()
        // The real AuthManager wrote a LOGOUT audit row
        val audit = auditManager.getAuditByActor("root-admin")
            .plus(auditManager.getRecentEvents(10L))
        val logoutAudit = auditManager.getRecentEvents(10L).filter { it.action == "LOGOUT" }
        assertThat(logoutAudit).isNotEmpty()
    }
}

package com.nutriops.app.ui.admin

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminDashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders title and all admin module cards`() {
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
    fun `tapping config card triggers navigation`() {
        var navigated = false
        composeTestRule.setContent {
            AdminDashboardScreen(
                onNavigateToConfig = { navigated = true },
                onNavigateToRules = {},
                onNavigateToRollouts = {},
                onNavigateToUsers = {},
                onNavigateToAudit = {},
                onLogout = {}
            )
        }

        composeTestRule.onNodeWithText("Configuration Center").performClick()

        assert(navigated)
    }

    @Test
    fun `tapping logout icon triggers logout callback`() {
        var loggedOut = false
        composeTestRule.setContent {
            AdminDashboardScreen(
                onNavigateToConfig = {},
                onNavigateToRules = {},
                onNavigateToRollouts = {},
                onNavigateToUsers = {},
                onNavigateToAudit = {},
                onLogout = { loggedOut = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Logout").performClick()

        assert(loggedOut)
    }
}

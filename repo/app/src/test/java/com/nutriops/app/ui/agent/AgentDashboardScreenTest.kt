package com.nutriops.app.ui.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentDashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders title and ticket management entry`() {
        composeTestRule.setContent {
            AgentDashboardScreen(onNavigateToTickets = {}, onLogout = {})
        }

        composeTestRule.onNodeWithText("Agent Dashboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ticket Management").assertIsDisplayed()
    }

    @Test
    fun `tapping the ticket management card navigates to tickets`() {
        var navigated = false
        composeTestRule.setContent {
            AgentDashboardScreen(
                onNavigateToTickets = { navigated = true },
                onLogout = {}
            )
        }

        composeTestRule.onNodeWithText("Ticket Management").performClick()
        assert(navigated)
    }

    @Test
    fun `tapping logout triggers logout callback`() {
        var loggedOut = false
        composeTestRule.setContent {
            AgentDashboardScreen(onNavigateToTickets = {}, onLogout = { loggedOut = true })
        }

        composeTestRule.onNodeWithContentDescription("Logout").performClick()
        assert(loggedOut)
    }
}

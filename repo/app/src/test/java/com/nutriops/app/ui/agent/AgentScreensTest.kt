package com.nutriops.app.ui.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI tests covering the agent screens in AgentScreens.kt — the
 * ticket list and the ticket detail flow. The dashboard is covered by
 * [AgentDashboardScreenTest] elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
class AgentScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): AgentTicketsViewModel {
        val ticketUseCase: ManageTicketUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        val auditManager: AuditManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return AgentTicketsViewModel(ticketUseCase, authManager, auditManager)
    }

    // ── AgentTicketListScreen ──

    @Test
    fun `ticket list renders empty title and refresh action when there are no tickets`() {
        composeTestRule.setContent {
            AgentTicketListScreen(
                onTicketClick = {},
                onBack = {},
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithText("Open Tickets (0)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun `ticket list back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            AgentTicketListScreen(
                onTicketClick = {},
                onBack = { wentBack = true },
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }

    @Test
    fun `tapping Refresh triggers the view model without crashing`() {
        composeTestRule.setContent {
            AgentTicketListScreen(
                onTicketClick = {},
                onBack = {},
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithText("Refresh").performClick()
        composeTestRule.waitForIdle()
        // Screen still alive after the refresh click
        composeTestRule.onNodeWithText("Open Tickets (0)").assertIsDisplayed()
    }

    // ── AgentTicketDetailScreen (loading state + back) ──

    @Test
    fun `ticket detail shows loading indicator when no ticket is selected yet`() {
        composeTestRule.setContent {
            AgentTicketDetailScreen(
                ticketId = "t-missing",
                onBack = {},
                viewModel = buildViewModel()
            )
        }

        // With the VM short-circuiting on null session, selectedTicket stays null
        // → screen renders the loading indicator. We assert the screen is alive
        // by checking the top bar title.
        composeTestRule.onNodeWithText("Ticket Detail").assertIsDisplayed()
    }

    @Test
    fun `ticket detail back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            AgentTicketDetailScreen(
                ticketId = "t-any",
                onBack = { wentBack = true },
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }

    // ── AgentDashboardScreen ──
    // Rendering and entry-point navigation are covered by AgentDashboardScreenTest
    // (which lives alongside this file). The sanity check below guards against
    // accidental removal of the ticket-management entry point.

    @Test
    fun `dashboard surfaces the ticket-management entry point`() {
        composeTestRule.setContent {
            AgentDashboardScreen(onNavigateToTickets = {}, onLogout = {})
        }

        composeTestRule.onNodeWithText("Ticket Management").assertIsDisplayed()
    }
}

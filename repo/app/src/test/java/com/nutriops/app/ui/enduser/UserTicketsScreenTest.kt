package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserTicketsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserTicketsViewModel {
        val ticketUseCase: ManageTicketUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserTicketsViewModel(ticketUseCase, authManager)
    }

    @Test
    fun `renders title and empty-state copy`() {
        composeTestRule.setContent {
            UserTicketsScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("My Tickets").assertIsDisplayed()
        composeTestRule.onNodeWithText("No tickets. Create one if you need support.").assertIsDisplayed()
    }

    @Test
    fun `Create Ticket FAB opens the ticket creation dialog`() {
        composeTestRule.setContent {
            UserTicketsScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Create Ticket").performClick()

        composeTestRule.onNodeWithText("Create Support Ticket").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subject").assertIsDisplayed()
        composeTestRule.onNodeWithText("Description").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type").assertIsDisplayed()
        composeTestRule.onNodeWithText("Priority").assertIsDisplayed()
    }

    @Test
    fun `Create button in dialog is disabled until Subject is entered`() {
        composeTestRule.setContent {
            UserTicketsScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Create Ticket").performClick()

        // Button starts disabled: tapping it must not dismiss the dialog.
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.onNodeWithText("Create Support Ticket").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            UserTicketsScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

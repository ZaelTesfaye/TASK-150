package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserDashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserDashboardViewModel {
        val messagingUseCase: ManageMessagingUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        // No active session — viewmodel's init will skip the unread-count lookup.
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserDashboardViewModel(messagingUseCase, authManager)
    }

    @Test
    fun `renders welcome message and primary feature cards`() {
        composeTestRule.setContent {
            UserDashboardScreen(
                onNavigateToProfile = {},
                onNavigateToMealPlan = {},
                onNavigateToLearningPlans = {},
                onNavigateToMessages = {},
                onNavigateToTickets = {},
                onLogout = {},
                viewModel = buildViewModel()
            )
        }

        val scrollable = composeTestRule.onNode(hasScrollAction())
        composeTestRule.onNodeWithText("Welcome back!").assertIsDisplayed()
        scrollable.performScrollToNode(hasText("My Profile"))
        composeTestRule.onNodeWithText("My Profile").assertIsDisplayed()
        scrollable.performScrollToNode(hasText("Weekly Meal Plan"))
        composeTestRule.onNodeWithText("Weekly Meal Plan").assertIsDisplayed()
        scrollable.performScrollToNode(hasText("Learning Plans"))
        composeTestRule.onNodeWithText("Learning Plans").assertIsDisplayed()
        scrollable.performScrollToNode(hasText("Messages & Reminders"))
        composeTestRule.onNodeWithText("Messages & Reminders").assertIsDisplayed()
        scrollable.performScrollToNode(hasText("Support Tickets"))
        composeTestRule.onNodeWithText("Support Tickets").assertIsDisplayed()
    }

    @Test
    fun `tapping profile card navigates to profile`() {
        var navigated = false
        composeTestRule.setContent {
            UserDashboardScreen(
                onNavigateToProfile = { navigated = true },
                onNavigateToMealPlan = {},
                onNavigateToLearningPlans = {},
                onNavigateToMessages = {},
                onNavigateToTickets = {},
                onLogout = {},
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithText("My Profile").performClick()
        assert(navigated)
    }

    @Test
    fun `tapping logout triggers logout callback`() {
        var loggedOut = false
        composeTestRule.setContent {
            UserDashboardScreen(
                onNavigateToProfile = {},
                onNavigateToMealPlan = {},
                onNavigateToLearningPlans = {},
                onNavigateToMessages = {},
                onNavigateToTickets = {},
                onLogout = { loggedOut = true },
                viewModel = buildViewModel()
            )
        }

        composeTestRule.onNodeWithContentDescription("Logout").performClick()
        assert(loggedOut)
    }
}

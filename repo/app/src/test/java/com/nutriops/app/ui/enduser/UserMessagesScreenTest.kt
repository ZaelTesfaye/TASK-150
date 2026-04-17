package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class UserMessagesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserMessagesViewModel {
        val messagingUseCase: ManageMessagingUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserMessagesViewModel(messagingUseCase, authManager)
    }

    @Test
    fun `renders title and tabs with unread-count badges`() {
        composeTestRule.setContent {
            UserMessagesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Messages & Todos").assertIsDisplayed()
        // With no data, unread count in each tab should be 0
        composeTestRule.onNodeWithText("Messages (0)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Todos (0)").assertIsDisplayed()
    }

    @Test
    fun `empty messages tab shows empty-state copy`() {
        composeTestRule.setContent {
            UserMessagesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun `switching to Todos tab shows todos empty-state copy`() {
        composeTestRule.setContent {
            UserMessagesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Todos (0)").performClick()

        composeTestRule.onNodeWithText("No todo items").assertIsDisplayed()
    }

    @Test
    fun `Mark All Read action is visible on Messages tab`() {
        composeTestRule.setContent {
            UserMessagesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Mark All Read").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            UserMessagesScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

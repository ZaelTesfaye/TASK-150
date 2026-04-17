package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminRolloutsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): AdminRolloutsViewModel {
        val configUseCase: ManageConfigUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return AdminRolloutsViewModel(configUseCase, authManager)
    }

    @Test
    fun `renders title and default no-rollout status`() {
        composeTestRule.setContent {
            AdminRolloutsScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Canary Rollouts").assertIsDisplayed()
        composeTestRule.onNodeWithText("No active rollout").assertIsDisplayed()
    }

    @Test
    fun `Start Canary button is enabled when no rollout is active`() {
        composeTestRule.setContent {
            AdminRolloutsScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Start Canary (10%)").assertIsEnabled()
        // Promote requires an active CANARY rollout — should be disabled initially
        composeTestRule.onNodeWithText("Promote to Full").assertIsNotEnabled()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            AdminRolloutsScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

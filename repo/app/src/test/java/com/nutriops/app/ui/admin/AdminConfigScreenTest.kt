package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
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
class AdminConfigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): AdminConfigViewModel {
        val configUseCase: ManageConfigUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return AdminConfigViewModel(configUseCase, authManager)
    }

    @Test
    fun `renders top app bar title and empty section headers`() {
        composeTestRule.setContent {
            AdminConfigScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Configuration Center").assertIsDisplayed()
        composeTestRule.onNodeWithText("Configurations (0)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Homepage Modules (0)").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            AdminConfigScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }

    @Test
    fun `floating action button is visible for adding a config`() {
        composeTestRule.setContent {
            AdminConfigScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Add Config").assertIsDisplayed()
    }
}

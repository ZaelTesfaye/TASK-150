package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminRulesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): AdminRulesViewModel {
        val ruleRepository: RuleRepository = mockk(relaxed = true)
        val evaluateRuleUseCase: EvaluateRuleUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        coEvery { ruleRepository.getAllActiveRules() } returns emptyList()
        return AdminRulesViewModel(ruleRepository, evaluateRuleUseCase, authManager)
    }

    @Test
    fun `renders title and empty-list header`() {
        composeTestRule.setContent {
            AdminRulesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Metrics & Rules Engine").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active Rules (0)").assertIsDisplayed()
    }

    @Test
    fun `shows Evaluate All button and Add Rule FAB`() {
        composeTestRule.setContent {
            AdminRulesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Evaluate All").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add Rule").assertIsDisplayed()
    }

    @Test
    fun `Add Rule FAB opens the create dialog`() {
        composeTestRule.setContent {
            AdminRulesScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Add Rule").performClick()

        composeTestRule.onNodeWithText("Create Rule").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name").assertIsDisplayed()
    }
}

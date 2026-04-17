package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserLearningPlanScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserLearningPlanViewModel {
        val useCase: ManageLearningPlanUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserLearningPlanViewModel(useCase, authManager)
    }

    @Test
    fun `renders title and Create Plan FAB`() {
        composeTestRule.setContent {
            UserLearningPlanScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Learning Plans").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Create Plan").assertIsDisplayed()
    }

    @Test
    fun `Create Plan FAB opens the create dialog with required fields`() {
        composeTestRule.setContent {
            UserLearningPlanScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Create Plan").performClick()

        composeTestRule.onNodeWithText("Create Learning Plan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Description").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            UserLearningPlanScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

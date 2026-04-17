package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.domain.usecase.mealplan.GenerateWeeklyPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserMealPlanScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserMealPlanViewModel {
        val generatePlanUseCase: GenerateWeeklyPlanUseCase = mockk(relaxed = true)
        val swapMealUseCase: SwapMealUseCase = mockk(relaxed = true)
        val mealPlanRepo: MealPlanRepository = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserMealPlanViewModel(generatePlanUseCase, swapMealUseCase, mealPlanRepo, authManager)
    }

    @Test
    fun `renders title and empty-state call to action`() {
        composeTestRule.setContent {
            UserMealPlanScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Weekly Meal Plan").assertIsDisplayed()
        composeTestRule.onNodeWithText("No active meal plan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Generate Weekly Plan").assertIsDisplayed()
    }

    @Test
    fun `Generate Weekly Plan button is present and clickable in empty state`() {
        composeTestRule.setContent {
            UserMealPlanScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Generate Weekly Plan").performClick()
        composeTestRule.waitForIdle()
        // With currentSession stubbed to null the VM short-circuits — the
        // assertion that matters is that the click did not crash the screen.
        composeTestRule.onNodeWithText("Weekly Meal Plan").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            UserMealPlanScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

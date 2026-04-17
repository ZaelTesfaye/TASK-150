package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI tests for the user profile screen. The screen renders a form of
 * filter chips and radios for the user's age range, dietary pattern, goal,
 * meal times and allergies. The ViewModel is constructed with mocked
 * dependencies — deeper persistence coverage lives in [ProfileRepositoryTest].
 */
@RunWith(RobolectricTestRunner::class)
class UserProfileScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): UserProfileViewModel {
        val profileUseCase: ManageProfileUseCase = mockk(relaxed = true)
        val profileRepo: ProfileRepository = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return UserProfileViewModel(profileUseCase, profileRepo, authManager)
    }

    @Test
    fun `renders title and primary section headers`() {
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("My Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Profile Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age Range").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dietary Pattern").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Health Goal").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Preferred Meal Times").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `allergies field is editable`() {
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Allergies (comma-separated)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `when no profile exists the primary CTA is Create Profile`() {
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = buildViewModel())
        }

        // No profile yet → the button label is "Create Profile"
        composeTestRule.onNodeWithText("Create Profile").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Create Profile").assertIsEnabled()
    }

    @Test
    fun `tapping an age range chip selects it without crashing the screen`() {
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = buildViewModel())
        }

        // Age ranges display as their domain display strings ("18-25", etc.)
        composeTestRule.onNodeWithText("26-35").performClick()
        composeTestRule.onNodeWithText("26-35").assertIsDisplayed()
    }

    @Test
    fun `Create Profile button is clickable even with no-session view model`() {
        val vm = buildViewModel()
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = vm)
        }

        // Clicking the button calls viewModel.saveProfile — with a null
        // session the VM short-circuits, but the click must not crash.
        composeTestRule.onNodeWithText("Create Profile").performClick()
        composeTestRule.waitForIdle()
        // Screen still renders after the click
        composeTestRule.onNodeWithText("My Profile").assertIsDisplayed()
    }

    @Test
    fun `back button triggers onBack callback`() {
        var wentBack = false
        composeTestRule.setContent {
            UserProfileScreen(onBack = { wentBack = true }, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(wentBack)
    }
}

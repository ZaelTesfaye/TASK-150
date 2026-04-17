package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminUsersScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): AdminUsersViewModel {
        val manageUsersUseCase: ManageUsersUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { authManager.currentSession } returns MutableStateFlow(null)
        return AdminUsersViewModel(manageUsersUseCase, authManager)
    }

    @Test
    fun `renders title and Add User FAB`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("User Management").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add User").assertIsDisplayed()
    }

    @Test
    fun `Add User FAB opens the create-user dialog with role selection`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Add User").performClick()

        composeTestRule.onNodeWithText("Create User").assertIsDisplayed()
        composeTestRule.onNodeWithText("Username").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        // Role chips each show the first 5 letters of the role name
        composeTestRule.onNodeWithText("ADMIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("AGENT").assertIsDisplayed()
        composeTestRule.onNodeWithText("END_U").assertIsDisplayed()
    }

    @Test
    fun `Cancel dismisses the create-user dialog`() {
        composeTestRule.setContent {
            AdminUsersScreen(onBack = {}, viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Add User").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onNodeWithText("Create User").assertDoesNotExist()
    }
}

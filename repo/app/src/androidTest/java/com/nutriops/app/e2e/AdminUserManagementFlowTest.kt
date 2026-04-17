package com.nutriops.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutriops.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test for the admin user-management flow.
 * Requires a booted emulator: `./gradlew :app:connectedDebugAndroidTest`
 *
 * Flow:
 *   Bootstrap admin (if fresh install) → login as admin → navigate to Users →
 *   create a new Agent → verify agent appears in the user list.
 */
@RunWith(AndroidJUnit4::class)
class AdminUserManagementFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun adminCanCreateAgentAndSeeThemInUserList() {
        composeRule.waitForIdle()
        loginAsAdmin()

        // Navigate: Admin Dashboard → Manage Users. The admin dashboard uses a
        // card labelled "User Management" as the entry point, but some UI
        // variants label it "Manage Users" — try both.
        val manageUsersEntry = listOf("User Management", "Manage Users")
        for (label in manageUsersEntry) {
            val found = runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
            if (found) {
                composeRule.onNodeWithText(label).performClick()
                break
            }
        }
        composeRule.waitForIdle()

        // Create a new Agent
        composeRule.onNodeWithContentDescription("Add User").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Username").performTextInput("e2e_agent")
        composeRule.onNodeWithText("Password").performTextInput("AgentPass1!")
        // Role chips show the first 5 letters — "AGENT"
        composeRule.onNodeWithText("AGENT").performClick()
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitForIdle()

        // New user should appear in the list
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("e2e_agent").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("e2e_agent").assertIsDisplayed()
    }

    // ── Helpers ──

    private fun loginAsAdmin() {
        val needsBootstrap = runCatching {
            composeRule.onNodeWithText("First-Time Setup").assertIsDisplayed()
        }.isSuccess
        if (needsBootstrap) {
            composeRule.onNodeWithText("Admin Username").performTextInput("admin")
            composeRule.onNodeWithText("Password (min 8 characters)").performTextInput("AdminPass1!")
            composeRule.onNodeWithText("Confirm Password").performTextInput("AdminPass1!")
            composeRule.onNodeWithText("Create Administrator Account").performClick()
        } else {
            composeRule.onNodeWithText("Username").performTextInput("admin")
            composeRule.onNodeWithText("Password").performTextInput("AdminPass1!")
            composeRule.onNodeWithText("Sign In").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("Administrator Dashboard").assertIsDisplayed()
            }.isSuccess
        }
    }
}

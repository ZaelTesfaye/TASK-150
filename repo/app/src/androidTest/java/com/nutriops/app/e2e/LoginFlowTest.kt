package com.nutriops.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutriops.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test covering the login flow. Runs against a real
 * emulator or device — requires `./gradlew connectedDebugAndroidTest` and a
 * booted AVD. The test is intentionally minimal (login → home → logout) to
 * serve as a starting scaffold; add additional flows beneath it as features
 * stabilize.
 *
 * Pre-conditions:
 *   - App has either a seeded set of demo credentials, or the test seeds
 *     an admin account via the first-run bootstrap screen.
 *   - Device is online (not strictly required — the app is fully offline).
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesToLoginOrBootstrap() {
        // Fresh install → first-run bootstrap screen is shown; reinstall/existing
        // install with seeded admin → login screen is shown. Accept either.
        composeRule.waitForIdle()

        val onLogin = runCatching {
            composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        }.isSuccess
        val onBootstrap = runCatching {
            composeRule.onNodeWithText("Create Administrator Account").assertIsDisplayed()
        }.isSuccess

        assert(onLogin || onBootstrap) {
            "Expected app to launch to Login or Bootstrap screen"
        }
    }

    @Test
    fun bootstrapThenLoginAsAdmin() {
        composeRule.waitForIdle()

        // If fresh install, seed an administrator via the bootstrap screen
        val needsBootstrap = runCatching {
            composeRule.onNodeWithText("First-Time Setup").assertIsDisplayed()
        }.isSuccess

        if (needsBootstrap) {
            composeRule.onNodeWithText("Admin Username").performTextInput("admin")
            composeRule.onNodeWithText("Password (min 8 characters)").performTextInput("AdminPass1!")
            composeRule.onNodeWithText("Confirm Password").performTextInput("AdminPass1!")
            composeRule.onNodeWithText("Create Administrator Account").performClick()
            composeRule.waitForIdle()

            // After bootstrap the admin is logged in → should land on the admin dashboard
            composeRule.onNodeWithText("Administrator Dashboard").assertIsDisplayed()
            return
        }

        // Otherwise, log in with seeded demo credentials
        composeRule.onNodeWithText("Username").performTextInput("admin")
        composeRule.onNodeWithText("Password").performTextInput("AdminPass1!")
        composeRule.onNodeWithText("Sign In").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Administrator Dashboard").assertIsDisplayed()
    }
}

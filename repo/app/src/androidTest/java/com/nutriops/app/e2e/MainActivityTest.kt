package com.nutriops.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutriops.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test that launches [MainActivity] and asserts the app reaches
 * the first Compose screen without crashing.
 *
 * Requires a booted emulator — run with
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityLaunchesAndNavHostRendersTheFirstScreen() {
        composeRule.waitForIdle()

        // On a fresh install the first screen is the bootstrap form; after a
        // prior run an admin already exists and the login screen is shown.
        // Accept either — the assertion we care about is that *something*
        // from the NavGraph rendered at all.
        val onBootstrap = runCatching {
            composeRule.onNodeWithText("First-Time Setup").assertIsDisplayed()
        }.isSuccess
        val onLogin = runCatching {
            composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        }.isSuccess
        val onDashboard = runCatching {
            composeRule.onNodeWithText("Welcome back!").assertIsDisplayed()
        }.isSuccess
        val onAdminDashboard = runCatching {
            composeRule.onNodeWithText("Administrator Dashboard").assertIsDisplayed()
        }.isSuccess
        val onAgentDashboard = runCatching {
            composeRule.onNodeWithText("Agent Dashboard").assertIsDisplayed()
        }.isSuccess

        assert(onBootstrap || onLogin || onDashboard || onAdminDashboard || onAgentDashboard) {
            "Expected NavGraph to render a known start destination"
        }
    }

    @Test
    fun activityScenarioResumesWithoutException() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                // If we got here, onCreate + setContent + NavHost inflation
                // all completed successfully. Validate the expected activity
                // subclass is live.
                assert(activity is MainActivity) {
                    "Expected MainActivity instance, got ${activity::class.java.name}"
                }
            }
        }
    }
}

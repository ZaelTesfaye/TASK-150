package com.nutriops.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `NutriOpsTheme composes without exception in light mode`() {
        composeTestRule.setContent {
            // dynamicColor = false forces the static LightColorScheme; otherwise
            // on API 31+ the theme would route to dynamicLightColorScheme which
            // requires a live wallpaper-backed context
            NutriOpsTheme(darkTheme = false, dynamicColor = false) {
                Text("light-mode")
            }
        }
        composeTestRule.onNodeWithText("light-mode").assertIsDisplayed()
    }

    @Test
    fun `NutriOpsTheme composes without exception in dark mode`() {
        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = true, dynamicColor = false) {
                Text("dark-mode")
            }
        }
        composeTestRule.onNodeWithText("dark-mode").assertIsDisplayed()
    }

    @Test
    fun `light mode exposes the static LightColorScheme primary tokens`() {
        var primary = Color.Unspecified
        var onPrimary = Color.Unspecified
        var background = Color.Unspecified
        var error = Color.Unspecified

        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = false, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
                onPrimary = MaterialTheme.colorScheme.onPrimary
                background = MaterialTheme.colorScheme.background
                error = MaterialTheme.colorScheme.error
            }
        }

        // Light primary == 0xFF2E7D32 (nutrition green)
        assertThat(primary).isEqualTo(Color(0xFF2E7D32))
        assertThat(onPrimary).isEqualTo(Color.White)
        assertThat(background).isEqualTo(Color(0xFFFAFAFA))
        assertThat(error).isEqualTo(Color(0xFFD32F2F))
    }

    @Test
    fun `dark mode exposes the static DarkColorScheme primary tokens`() {
        var primary = Color.Unspecified
        var background = Color.Unspecified
        var surface = Color.Unspecified

        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = true, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
                background = MaterialTheme.colorScheme.background
                surface = MaterialTheme.colorScheme.surface
            }
        }

        assertThat(primary).isEqualTo(Color(0xFF81C784))
        assertThat(background).isEqualTo(Color(0xFF121212))
        assertThat(surface).isEqualTo(Color(0xFF1E1E1E))
    }

    @Test
    fun `typography resolves in the theme without crashing`() {
        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = false, dynamicColor = false) {
                Text(
                    text = "typography-check",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        composeTestRule.onNodeWithText("typography-check").assertIsDisplayed()
    }

    @Test
    fun `shapes token resolves in the theme`() {
        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = false, dynamicColor = false) {
                // Shapes come from the Material defaults since the theme does
                // not override them. Reading them must not crash.
                val shapes = MaterialTheme.shapes
                assertThat(shapes).isNotNull()
                Text("shapes-check")
            }
        }
        composeTestRule.onNodeWithText("shapes-check").assertIsDisplayed()
    }

    @Test
    fun `dynamicColor flag does not crash on SDK below 31 - falls back to static schemes`() {
        // Robolectric runs against whatever SDK was requested. The fallback
        // branch inside NutriOpsTheme uses the static scheme when SDK < S.
        composeTestRule.setContent {
            NutriOpsTheme(darkTheme = false, dynamicColor = true) {
                Text("dynamic-color-requested")
            }
        }
        composeTestRule.onNodeWithText("dynamic-color-requested").assertIsDisplayed()
    }

    @Test
    fun `theme content re-composes on darkTheme flip`() {
        composeTestRule.setContent {
            var dark by remember { mutableStateOf(false) }
            NutriOpsTheme(darkTheme = dark, dynamicColor = false) {
                Text(if (dark) "DARK" else "LIGHT")
                androidx.compose.material3.TextButton(onClick = { dark = !dark }) {
                    Text("toggle")
                }
            }
        }

        composeTestRule.onNodeWithText("LIGHT").assertIsDisplayed()
        composeTestRule.onNodeWithText("toggle").performClick()
        composeTestRule.onNodeWithText("DARK").assertIsDisplayed()
    }
}

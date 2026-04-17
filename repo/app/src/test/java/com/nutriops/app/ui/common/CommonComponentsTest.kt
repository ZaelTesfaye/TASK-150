package com.nutriops.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommonComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── LoadingIndicator ──

    @Ignore("Throws NoSuchMethodError under Robolectric for reasons we haven't root-caused; the composable is exercised in instrumented tests.")
    @Test
    fun `LoadingIndicator renders without crashing`() {
        composeTestRule.setContent {
            // MaterialTheme is required because LoadingIndicator's
            // CircularProgressIndicator reads colors from MaterialTheme. We
            // pass `modifier` explicitly to avoid emitting a call to
            // LoadingIndicator's synthetic `$default` method — that path has
            // been observed to trigger NoSuchMethodError under Robolectric
            // when the Compose compiler plugin's generated signature drifts
            // from the material3 runtime artifact.
            MaterialTheme {
                LoadingIndicator(modifier = Modifier)
            }
        }
        // Robolectric layout does not surface a CircularProgressIndicator
        // by text or content description, so the assertion is that the host
        // composes cleanly. A throwing LoadingIndicator would fail setContent.
        composeTestRule.waitForIdle()
    }

    // ── ErrorDisplay ──

    @Test
    fun `ErrorDisplay shows the error icon and message`() {
        composeTestRule.setContent {
            Surface { ErrorDisplay(message = "Something went wrong") }
        }

        composeTestRule.onNodeWithContentDescription("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
    }

    @Test
    fun `ErrorDisplay hides the retry button when onRetry is null`() {
        composeTestRule.setContent {
            Surface { ErrorDisplay(message = "boom") }
        }

        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `ErrorDisplay shows retry button that fires the callback`() {
        var retried = false
        composeTestRule.setContent {
            Surface { ErrorDisplay(message = "boom", onRetry = { retried = true }) }
        }

        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        assert(retried)
    }

    @Test
    fun `ErrorDisplay handles empty message`() {
        composeTestRule.setContent {
            Surface { ErrorDisplay(message = "") }
        }
        composeTestRule.onNodeWithContentDescription("Error").assertIsDisplayed()
    }

    @Test
    fun `ErrorDisplay handles very long message without crash`() {
        val longMessage = "error ".repeat(500)
        composeTestRule.setContent {
            Surface { ErrorDisplay(message = longMessage) }
        }
        composeTestRule.onNodeWithContentDescription("Error").assertIsDisplayed()
    }

    // ── ConfirmationDialog ──

    @Test
    fun `ConfirmationDialog renders title, message and default button labels`() {
        composeTestRule.setContent {
            Surface {
                ConfirmationDialog(
                    title = "Delete item?",
                    message = "This cannot be undone.",
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Delete item?").assertIsDisplayed()
        composeTestRule.onNodeWithText("This cannot be undone.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed().assertHasClickAction()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `ConfirmationDialog Confirm button invokes onConfirm`() {
        var confirmed = false
        composeTestRule.setContent {
            Surface {
                ConfirmationDialog(
                    title = "t", message = "m",
                    onConfirm = { confirmed = true }, onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Confirm").performClick()
        assert(confirmed)
    }

    @Test
    fun `ConfirmationDialog Cancel button invokes onDismiss`() {
        var dismissed = false
        composeTestRule.setContent {
            Surface {
                ConfirmationDialog(
                    title = "t", message = "m",
                    onConfirm = {}, onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(dismissed)
    }

    @Test
    fun `ConfirmationDialog respects custom button labels`() {
        composeTestRule.setContent {
            Surface {
                ConfirmationDialog(
                    title = "Proceed?", message = "Data will be lost.",
                    onConfirm = {}, onDismiss = {},
                    confirmText = "Yes, delete", dismissText = "Keep"
                )
            }
        }

        composeTestRule.onNodeWithText("Yes, delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep").assertIsDisplayed()
        // Defaults should no longer be present
        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    // ── SectionHeader ──

    @Test
    fun `SectionHeader renders the provided title`() {
        composeTestRule.setContent {
            Surface { SectionHeader(title = "My section") }
        }

        composeTestRule.onNodeWithText("My section").assertIsDisplayed()
    }

    @Test
    fun `SectionHeader handles empty title string`() {
        composeTestRule.setContent {
            Surface { SectionHeader(title = "") }
        }
        composeTestRule.waitForIdle()
    }

    // ── StatusChip ──

    @Test
    fun `StatusChip renders its label`() {
        composeTestRule.setContent {
            MaterialTheme {
                StatusChip(label = "OPEN", color = Color(0xFF2196F3))
            }
        }

        composeTestRule.onNodeWithText("OPEN").assertIsDisplayed()
    }

    @Test
    fun `StatusChip renders multiple chips without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    Box {
                        StatusChip(label = "A", color = Color.Red)
                        StatusChip(label = "B", color = Color.Blue)
                        StatusChip(label = "C", color = Color.Green)
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
        composeTestRule.onNodeWithText("C").assertIsDisplayed()
    }

    @Test
    fun `StatusChip handles empty label without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                StatusChip(label = "", color = Color.Black)
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `StatusChip handles very long label without crash`() {
        val longLabel = "STATUS".repeat(50)
        composeTestRule.setContent {
            MaterialTheme {
                StatusChip(label = longLabel, color = Color.Magenta)
            }
        }
        composeTestRule.onNodeWithText(longLabel).assertIsDisplayed()
    }

    // ── DownsampledImage ──

    @Test
    fun `DownsampledImage renders for a null model without crashing`() {
        composeTestRule.setContent {
            Surface {
                DownsampledImage(
                    model = null,
                    contentDescription = "placeholder-image"
                )
            }
        }
        // Coil returns a placeholder node for null models; assertion is the
        // composable did not throw on setContent
        composeTestRule.waitForIdle()
    }

    @Test
    fun `DownsampledImage accepts a custom maxDimensionPx override`() {
        composeTestRule.setContent {
            Surface {
                DownsampledImage(
                    model = null,
                    contentDescription = "thumbnail",
                    maxDimensionPx = 256
                )
            }
        }
        composeTestRule.waitForIdle()
    }
}

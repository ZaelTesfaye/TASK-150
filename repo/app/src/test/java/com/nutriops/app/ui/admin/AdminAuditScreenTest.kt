package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nutriops.app.audit.AuditManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminAuditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders title and empty-state helper copy`() {
        val auditManager: AuditManager = mockk(relaxed = true)
        every { auditManager.getRecentEvents(any()) } returns emptyList()

        composeTestRule.setContent {
            AdminAuditScreen(onBack = {}, viewModel = AdminAuditViewModel(auditManager))
        }

        composeTestRule.onNodeWithText("Audit Trail (Immutable)").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 events (append-only, no modifications allowed)").assertIsDisplayed()
    }

    @Test
    fun `Refresh action re-queries the audit manager`() {
        val auditManager: AuditManager = mockk(relaxed = true)
        every { auditManager.getRecentEvents(any()) } returns emptyList()
        val vm = AdminAuditViewModel(auditManager)

        composeTestRule.setContent {
            AdminAuditScreen(onBack = {}, viewModel = vm)
        }

        composeTestRule.onNodeWithText("Refresh").performClick()
        composeTestRule.waitForIdle()

        // init{} + one refresh click = at least two calls
        verify(atLeast = 2) { auditManager.getRecentEvents(any()) }
    }
}

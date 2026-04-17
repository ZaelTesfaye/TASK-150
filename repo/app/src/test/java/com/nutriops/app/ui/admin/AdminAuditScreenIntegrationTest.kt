package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminAuditScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        // Seed three real audit rows
        repeat(3) { idx ->
            auditManager.log(
                entityType = "Ticket", entityId = "t$idx",
                action = AuditAction.CREATE,
                actorId = "admin1", actorRole = Role.ADMINISTRATOR
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `audit screen surfaces the real row count from the in-memory DB`() {
        composeTestRule.setContent {
            AdminAuditScreen(onBack = {}, viewModel = AdminAuditViewModel(auditManager))
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("3 events (append-only, no modifications allowed)")
            .assertIsDisplayed()
    }

    @Test
    fun `Refresh button re-queries the DB and picks up newly written rows`() {
        val vm = AdminAuditViewModel(auditManager)
        composeTestRule.setContent { AdminAuditScreen(onBack = {}, viewModel = vm) }

        // Write two more audit rows after the screen has rendered
        auditManager.log("Order", "o1", AuditAction.CREATE, "admin1", Role.ADMINISTRATOR)
        auditManager.log("Order", "o2", AuditAction.CREATE, "admin1", Role.ADMINISTRATOR)

        composeTestRule.onNodeWithText("Refresh").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("5 events (append-only, no modifications allowed)")
            .assertIsDisplayed()
        assertThat(auditManager.getRecentEvents(10L)).hasSize(5)
    }
}

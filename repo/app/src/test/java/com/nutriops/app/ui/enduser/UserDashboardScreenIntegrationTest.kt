package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserDashboardScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var messagingUseCase: ManageMessagingUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        val audit = AuditManager(database)
        authManager = AuthManager(database, audit)
        authManager.bootstrapAdmin("root", "AdminPass1!"); authManager.logout()
        authManager.register("alice", "AlicePass1!")

        val messageRepo = MessageRepository(database)
        messagingUseCase = ManageMessagingUseCase(messageRepo)

        // Seed three unread messages for the active user
        runBlocking {
            repeat(3) {
                messageRepo.sendMessage(
                    userId = authManager.currentUserId, templateId = null,
                    title = "Unread $it", body = "body",
                    messageType = MessageType.NOTIFICATION.name,
                    triggerEvent = TriggerEvent.TICKET_UPDATED.key
                )
            }
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `dashboard renders welcome message and real unread badge from the DB`() {
        composeTestRule.setContent {
            UserDashboardScreen(
                onNavigateToProfile = {}, onNavigateToMealPlan = {},
                onNavigateToLearningPlans = {}, onNavigateToMessages = {},
                onNavigateToTickets = {}, onLogout = {},
                viewModel = UserDashboardViewModel(messagingUseCase, authManager)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Welcome back!").assertIsDisplayed()
        // Unread badge reflects real count (3)
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }
}

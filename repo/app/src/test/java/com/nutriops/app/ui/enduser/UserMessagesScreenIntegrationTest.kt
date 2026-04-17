package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
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
class UserMessagesScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var messagingUseCase: ManageMessagingUseCase
    private lateinit var messageRepo: MessageRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        val audit = AuditManager(database)
        authManager = AuthManager(database, audit)
        authManager.bootstrapAdmin("root", "AdminPass1!"); authManager.logout()
        authManager.register("alice", "AlicePass1!")

        messageRepo = MessageRepository(database)
        messagingUseCase = ManageMessagingUseCase(messageRepo)

        runBlocking {
            messageRepo.sendMessage(
                userId = authManager.currentUserId, templateId = null,
                title = "Hello Alice", body = "welcome body",
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_CREATED.key
            )
            messageRepo.createTodo(
                userId = authManager.currentUserId,
                title = "First todo", description = "do it",
                dueDate = null, relatedEntityType = null, relatedEntityId = null
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `messages tab shows real unread count and the seeded message`() {
        composeTestRule.setContent {
            UserMessagesScreen(
                onBack = {},
                viewModel = UserMessagesViewModel(messagingUseCase, authManager)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Messages (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello Alice").assertIsDisplayed()
    }

    @Test
    fun `Mark All Read flips the real DB row for the active user`() = runBlocking {
        val vm = UserMessagesViewModel(messagingUseCase, authManager)
        composeTestRule.setContent { UserMessagesScreen(onBack = {}, viewModel = vm) }

        composeTestRule.onNodeWithText("Mark All Read").performClick()
        composeTestRule.waitForIdle()

        assertThat(messageRepo.getUnreadCount(authManager.currentUserId)).isEqualTo(0L)
    }

    @Test
    fun `Todos tab renders the real seeded todo`() {
        val vm = UserMessagesViewModel(messagingUseCase, authManager)
        composeTestRule.setContent { UserMessagesScreen(onBack = {}, viewModel = vm) }

        composeTestRule.onNodeWithText("Todos (1)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("First todo").assertIsDisplayed()
    }
}

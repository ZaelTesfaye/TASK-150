package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UserMessagesScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var messagingUseCase: ManageMessagingUseCase
    private lateinit var messageRepo: MessageRepository
    private val activeVms = mutableListOf<ViewModel>()

    private fun <T : ViewModel> T.tracked(): T = also { activeVms.add(it) }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        // Cancel every ViewModel's viewModelScope up front so no more
        // coroutines can be launched against the driver we're about to
        // close. composeTestRule disposes the composition AFTER @After
        // runs, so without this explicit cancel the VM is still live when
        // driver.close() fires and a late IO refresh (post-markAllAsRead,
        // etc.) races the close, surfacing as UncaughtExceptionsBeforeTest
        // on the next test's TestScope init.
        activeVms.forEach { it.viewModelScope.cancel() }
        activeVms.clear()
        Dispatchers.resetMain()
        Thread.sleep(500)
        driver.close()
    }

    @Test
    fun `messages tab shows real unread count and the seeded message`() {
        composeTestRule.setContent {
            UserMessagesScreen(
                onBack = {},
                viewModel = UserMessagesViewModel(messagingUseCase, authManager).tracked()
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Messages (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello Alice").assertIsDisplayed()
    }

    @Test
    fun `Mark All Read flips the real DB row for the active user`(): Unit = runBlocking {
        val vm = UserMessagesViewModel(messagingUseCase, authManager).tracked()
        composeTestRule.setContent { UserMessagesScreen(onBack = {}, viewModel = vm) }

        composeTestRule.onNodeWithText("Mark All Read").performClick()
        // markAllAsRead is fired on viewModelScope → repository withContext(IO);
        // waitForIdle only drains Compose, so poll the DB until the write is
        // observable before asserting. Otherwise the test races the write and
        // leaves a live coroutine that surfaces as UncaughtExceptionsBeforeTest
        // when the TestScope finalizes.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { messageRepo.getUnreadCount(authManager.currentUserId) == 0L }
        }

        assertThat(messageRepo.getUnreadCount(authManager.currentUserId)).isEqualTo(0L)
    }

    @Test
    fun `Todos tab renders the real seeded todo`() {
        val vm = UserMessagesViewModel(messagingUseCase, authManager).tracked()
        composeTestRule.setContent { UserMessagesScreen(onBack = {}, viewModel = vm) }

        composeTestRule.onNodeWithText("Todos (1)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("First todo").assertIsDisplayed()
    }
}

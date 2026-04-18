package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.security.testing.JvmEncryptionManager
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
class UserTicketsScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var ticketUseCase: ManageTicketUseCase
    private val activeVms = mutableListOf<ViewModel>()

    private fun <T : ViewModel> T.tracked(): T = also { activeVms.add(it) }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        authManager.bootstrapAdmin("root", "AdminPass1!"); authManager.logout()
        authManager.register("alice", "AlicePass1!")

        val ticketRepo = TicketRepository(database, auditManager, JvmEncryptionManager())
        val messageRepo = MessageRepository(database)
        ticketUseCase = ManageTicketUseCase(ticketRepo, messageRepo)

        // Seed a ticket for this user
        runBlocking {
            ticketUseCase.createTicket(
                userId = authManager.currentUserId,
                ticketType = TicketType.DELAY,
                priority = TicketPriority.MEDIUM,
                subject = "My late order",
                description = "Order #42",
                actorId = authManager.currentUserId,
                actorRole = Role.END_USER
            )
        }
    }

    @After
    fun tearDown() {
        activeVms.forEach { it.viewModelScope.cancel() }
        activeVms.clear()
        Dispatchers.resetMain()
        Thread.sleep(500)
        driver.close()
    }

    @Test
    fun `user ticket list renders the tickets owned by the active user`() {
        composeTestRule.setContent {
            UserTicketsScreen(onBack = {}, viewModel = UserTicketsViewModel(ticketUseCase, authManager).tracked())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("My late order").assertIsDisplayed()
    }

    @Test
    fun `creating a ticket via the view model writes a real row visible to the user`(): Unit = runBlocking {
        val vm = UserTicketsViewModel(ticketUseCase, authManager).tracked()
        composeTestRule.setContent { UserTicketsScreen(onBack = {}, viewModel = vm) }

        vm.createTicket(TicketType.DISPUTE, TicketPriority.HIGH, "Dispute #1", "Wrong item received")
        // createTicket is launched on viewModelScope and then suspends into
        // Dispatchers.IO inside the repository, so waitForIdle alone cannot
        // guarantee the write is visible. Poll the DB until the row lands.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                ticketUseCase.getTicketsByUser(
                    authManager.currentUserId, authManager.currentUserId, Role.END_USER
                ).any { it.subject == "Dispute #1" }
            }
        }

        val tickets = ticketUseCase.getTicketsByUser(
            authManager.currentUserId, authManager.currentUserId, Role.END_USER
        )
        assertThat(tickets.map { it.subject }).containsAtLeast("My late order", "Dispute #1")
    }
}

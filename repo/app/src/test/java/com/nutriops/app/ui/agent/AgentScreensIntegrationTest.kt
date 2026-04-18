package com.nutriops.app.ui.agent

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
class AgentScreensIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var ticketRepository: TicketRepository
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
        authManager.bootstrapAdmin("root-admin", "AdminPass1!")
        // Seed an agent session
        runBlocking {
            authManager.logout()
            authManager.register("agent-in-test", "AgentPass1!")
            // Elevate the registered end-user to an agent role directly in the DB
            val agentUser = database.usersQueries.getUserByUsername("agent-in-test").executeAsOne()
            database.usersQueries.updateUser(
                username = agentUser.username, passwordHash = agentUser.passwordHash,
                role = Role.AGENT.name, isActive = 1, isLocked = 0,
                failedLoginAttempts = 0, lockoutUntil = null,
                updatedAt = agentUser.updatedAt, id = agentUser.id
            )
            authManager.logout()
            authManager.login("agent-in-test", "AgentPass1!")
        }
        ticketRepository = TicketRepository(database, auditManager, JvmEncryptionManager())
        val messageRepo = MessageRepository(database)
        ticketUseCase = ManageTicketUseCase(ticketRepository, messageRepo)

        // Seed two real tickets filed by end users, waiting in the open queue
        runBlocking {
            ticketUseCase.createTicket(
                userId = "end-user-1", ticketType = TicketType.DELAY,
                priority = TicketPriority.HIGH, subject = "Late delivery",
                description = "Package late", actorId = "end-user-1",
                actorRole = Role.END_USER
            )
            ticketUseCase.createTicket(
                userId = "end-user-2", ticketType = TicketType.DISPUTE,
                priority = TicketPriority.MEDIUM, subject = "Wrong item",
                description = "Received wrong item", actorId = "end-user-2",
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

    private fun viewModel(): AgentTicketsViewModel =
        AgentTicketsViewModel(ticketUseCase, authManager, auditManager).tracked()

    @Test
    fun `open ticket list shows the real seeded tickets`() {
        composeTestRule.setContent {
            AgentTicketListScreen(onTicketClick = {}, onBack = {}, viewModel = viewModel())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Open Tickets (2)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Late delivery").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wrong item").assertIsDisplayed()
    }

    @Test
    fun `assigning a ticket to self updates the DB and the agent ticket inbox`(): Unit = runBlocking {
        val vm = viewModel()
        composeTestRule.setContent { AgentTicketListScreen(onTicketClick = {}, onBack = {}, viewModel = vm) }

        val ticketId = database.ticketsQueries.getTicketsByStatus("OPEN").executeAsList().first().id
        vm.assignToSelf(ticketId)
        // assignToSelf launches on viewModelScope and then suspends into
        // Dispatchers.IO (the repository wraps writes in withContext(IO)), so
        // waitForIdle alone cannot guarantee the update is visible. Poll the
        // DB for the status change before asserting.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            database.ticketsQueries.getTicketById(ticketId).executeAsOne().status == "ASSIGNED"
        }

        val ticket = database.ticketsQueries.getTicketById(ticketId).executeAsOne()
        assertThat(ticket.status).isEqualTo("ASSIGNED")
        assertThat(ticket.agentId).isEqualTo(authManager.currentUserId)
    }
}

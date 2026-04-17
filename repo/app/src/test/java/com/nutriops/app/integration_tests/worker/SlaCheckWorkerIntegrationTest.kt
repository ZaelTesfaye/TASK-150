package com.nutriops.app.integration_tests.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.testing.JvmEncryptionManager
import com.nutriops.app.worker.SlaCheckWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Integration test for [SlaCheckWorker] with **zero** mocks or spies on the
 * execution path. Every collaborator is real -- the worker runs against an
 * in-memory SQLDelight database, a real [TicketRepository] (with real
 * AES-256-GCM via [JvmEncryptionManager]), and a real [MessageRepository]
 * wrapped by a real [ManageMessagingUseCase]. Assertions observe the DB
 * rows the worker writes, not method invocations.
 *
 * Uses [WorkManagerTestInitHelper] to initialize WorkManager with a
 * synchronous executor so the worker can be driven directly via
 * [TestListenableWorkerBuilder] without racing the main-thread scheduler.
 */
@RunWith(RobolectricTestRunner::class)
class SlaCheckWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var messagingUseCase: ManageMessagingUseCase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        )

        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)

        ticketRepository = TicketRepository(database, auditManager, JvmEncryptionManager())
        messageRepository = MessageRepository(database)
        messagingUseCase = ManageMessagingUseCase(messageRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun buildWorker(): SlaCheckWorker =
        TestListenableWorkerBuilder<SlaCheckWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker =
                    SlaCheckWorker(appContext, workerParameters, ticketRepository, messagingUseCase)
            })
            .build()

    private suspend fun createTicket(
        userId: String = "user1",
        agentId: String? = null
    ): String {
        val id = ticketRepository.createTicket(
            userId = userId, ticketType = TicketType.DELAY,
            priority = TicketPriority.MEDIUM,
            subject = "subj", description = "desc",
            actorId = userId, actorRole = Role.END_USER
        ).getOrNull()!!
        if (agentId != null) {
            ticketRepository.assignTicket(id, agentId, agentId, Role.AGENT)
        }
        return id
    }

    /** Back-date the insert-time-only SLA deadline column directly. */
    private fun backdateFirstResponseDue(ticketId: String, target: LocalDateTime) {
        driver.execute(
            identifier = null,
            sql = "UPDATE Tickets SET slaFirstResponseDue = '${target.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}' WHERE id = '$ticketId'",
            parameters = 0,
            binders = null
        )
    }

    // ── No-op cases ──

    @Test
    fun `doWork returns success and writes no messages when there are no open tickets`() = runBlocking {
        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(messageRepository.getMessagesByUserId("user1")).isEmpty()
    }

    // ── Persistence-based breach detection ──

    @Test
    fun `a breached ticket causes a real SLA_BREACHED message row to be inserted`() = runBlocking {
        val ticketId = createTicket(userId = "user1")
        // First-response due 1 hour in the past; never responded
        backdateFirstResponseDue(ticketId, LocalDateTime.now().minusHours(1))

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val messages = messageRepository.getMessagesByUserId("user1")
        assertThat(messages).isNotEmpty()
        assertThat(messages.map { it.triggerEvent })
            .contains(TriggerEvent.SLA_BREACHED.key)
    }

    @Test
    fun `a near-deadline ticket causes a real SLA_WARNING message row for the agent`() = runBlocking {
        val ticketId = createTicket(userId = "user1", agentId = "agent-abc")
        // Within the one-hour warning window and still not responded
        backdateFirstResponseDue(ticketId, LocalDateTime.now().plusMinutes(30))

        buildWorker().doWork()

        // Warning is addressed to the assigned agent
        val agentMessages = messageRepository.getMessagesByUserId("agent-abc")
        assertThat(agentMessages.map { it.triggerEvent })
            .contains(TriggerEvent.SLA_WARNING.key)
    }

    @Test
    fun `a ticket with a first-response timestamp no longer triggers SLA_BREACHED`() = runBlocking {
        val ticketId = createTicket(userId = "user1")
        backdateFirstResponseDue(ticketId, LocalDateTime.now().minusHours(2))
        // Mark that the agent has already responded
        database.ticketsQueries.updateTicketSlaFirstResponse(
            nowIso(), nowIso(), ticketId
        )

        buildWorker().doWork()

        val messages = messageRepository.getMessagesByUserId("user1")
        assertThat(messages.map { it.triggerEvent })
            .doesNotContain(TriggerEvent.SLA_BREACHED.key)
    }

    @Test
    fun `processing many breached tickets writes one message row per ticket`() = runBlocking {
        val expectedBreached = 3
        repeat(expectedBreached) { idx ->
            val id = createTicket(userId = "user-$idx")
            backdateFirstResponseDue(id, LocalDateTime.now().minusHours(1))
        }
        // And one healthy ticket that should NOT produce a message
        createTicket(userId = "user-healthy")

        buildWorker().doWork()

        // Every breached user got exactly one SLA_BREACHED message
        for (idx in 0 until expectedBreached) {
            val msgs = messageRepository.getMessagesByUserId("user-$idx")
            val breachMsgs = msgs.filter { it.triggerEvent == TriggerEvent.SLA_BREACHED.key }
            assertThat(breachMsgs).hasSize(1)
        }
        // Healthy user has no messages at all
        assertThat(messageRepository.getMessagesByUserId("user-healthy")).isEmpty()
    }

    // ── helpers ──

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

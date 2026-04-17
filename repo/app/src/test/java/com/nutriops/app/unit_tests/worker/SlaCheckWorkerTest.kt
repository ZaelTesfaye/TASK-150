package com.nutriops.app.unit_tests.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.data.repository.TicketRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.EncryptionManager
import com.nutriops.app.worker.SlaCheckWorker
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Worker test against a real in-memory SQLDelight database and real
 * [TicketRepository]. The messaging use case is wrapped in a spy so we can
 * verify outbound SLA triggers (external boundary) were dispatched — but the
 * worker's internal orchestration (which tickets are breached, which are
 * warnings) is computed against real data, not a mock.
 */
@RunWith(RobolectricTestRunner::class)
class SlaCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var messagingUseCase: ManageMessagingUseCase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        encryptionManager = mockk {
            every { encrypt(any()) } answers { "ENC(${firstArg<String>()})" }
            every { decrypt(any()) } answers {
                val v = firstArg<String>()
                if (v.startsWith("ENC(")) v.removePrefix("ENC(").removeSuffix(")") else v
            }
        }
        ticketRepository = TicketRepository(database, auditManager, encryptionManager)
        messageRepository = MessageRepository(database)
        // Spy so we can verify outbound messages were emitted, but the
        // underlying send goes to the real DB.
        messagingUseCase = spyk(ManageMessagingUseCase(messageRepository))
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

    private suspend fun createTicket(userId: String): String = ticketRepository.createTicket(
        userId = userId, ticketType = TicketType.DELAY, priority = TicketPriority.MEDIUM,
        subject = "s", description = "d", actorId = userId, actorRole = Role.END_USER
    ).getOrNull()!!

    @Test
    fun `doWork returns success when there are no open tickets`() = runBlocking {
        val result = buildWorker().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `doWork dispatches SLA_BREACHED for tickets whose first-response deadline has passed`() = runBlocking {
        val ticketId = createTicket("user1")
        setFirstResponseDue(ticketId, LocalDateTime.now().minusHours(1))

        buildWorker().doWork()

        coVerify {
            messagingUseCase.sendTriggeredMessage(
                userId = "user1",
                triggerEvent = TriggerEvent.SLA_BREACHED,
                variables = any()
            )
        }
        // The breach notification is also persisted as a real message row
        val msgs = messageRepository.getMessagesByUserId("user1")
        assertThat(msgs).isNotEmpty()
    }

    @Test
    fun `doWork dispatches SLA_WARNING for tickets whose deadline is within one hour`() = runBlocking {
        val ticketId = createTicket("user1")
        setFirstResponseDue(ticketId, LocalDateTime.now().plusMinutes(30))

        buildWorker().doWork()

        coVerify {
            messagingUseCase.sendTriggeredMessage(
                userId = any(),
                triggerEvent = TriggerEvent.SLA_WARNING,
                variables = any()
            )
        }
    }

    @Test
    fun `doWork does not alert on tickets that already have a first-response timestamp`() = runBlocking {
        val ticketId = createTicket("user1")
        setFirstResponseDue(ticketId, LocalDateTime.now().minusHours(2))
        // Mark first response already delivered
        database.ticketsQueries.updateTicketSlaFirstResponse(
            nowIso(), nowIso(), ticketId
        )

        buildWorker().doWork()

        coVerify(exactly = 0) {
            messagingUseCase.sendTriggeredMessage(any(), TriggerEvent.SLA_BREACHED, any())
        }
    }

    // ── helpers ──

    /**
     * `slaFirstResponseDue` is set at insert time only, so use the driver
     * directly to simulate passage of time for the seeded ticket.
     */
    private fun setFirstResponseDue(ticketId: String, due: LocalDateTime) {
        driver.execute(
            identifier = null,
            sql = "UPDATE Tickets SET slaFirstResponseDue = '${due.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}' WHERE id = '$ticketId'",
            parameters = 0,
            binders = null
        )
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

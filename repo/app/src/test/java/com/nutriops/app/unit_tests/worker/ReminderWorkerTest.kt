package com.nutriops.app.unit_tests.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.ReminderStatus
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.worker.ReminderWorker
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
 * [MessageRepository] + [ManageMessagingUseCase]. The mock depth was reduced
 * so the worker's delivery path is exercised end-to-end: pending reminders
 * are seeded, the worker runs, and we assert the repository state has
 * advanced (delivered/skipped) — not just that a mock was called.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkerTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var messagingUseCase: ManageMessagingUseCase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        messageRepository = MessageRepository(database)
        messagingUseCase = ManageMessagingUseCase(messageRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun buildWorker(): ReminderWorker =
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = ReminderWorker(appContext, workerParameters, messagingUseCase)
            })
            .build()

    @Test
    fun `doWork returns success when there are no pending reminders`() = runBlocking {
        val result = buildWorker().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `doWork advances pending reminders that are due into a terminal state`() = runBlocking {
        // Seed a reminder due in the past
        val past = LocalDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val reminderId = messageRepository.scheduleReminder(
            userId = "user1", messageId = null, title = "Take a walk",
            scheduledAt = past
        ).getOrNull()!!

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Reminder is no longer PENDING — it either delivered or was skipped
        val reminder = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        assertThat(reminder.status).isNotEqualTo(ReminderStatus.PENDING.name)
    }

    @Test
    fun `doWork ignores reminders scheduled in the future`() = runBlocking {
        val future = LocalDateTime.now().plusHours(4).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val reminderId = messageRepository.scheduleReminder(
            "user1", null, "Later", future
        ).getOrNull()!!

        buildWorker().doWork()

        val reminder = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        assertThat(reminder.status).isEqualTo(ReminderStatus.PENDING.name)
    }

    @Test
    fun `doWork leaves non-PENDING reminders alone`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(10).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val reminderId = messageRepository.scheduleReminder("user1", null, "t", past).getOrNull()!!
        // Manually mark delivered
        database.messagesQueries.updateReminderStatus(
            ReminderStatus.DELIVERED.name,
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            reminderId
        )

        buildWorker().doWork()

        val reminder = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        assertThat(reminder.status).isEqualTo(ReminderStatus.DELIVERED.name)
    }

    // ── Explicit observable side-effect assertions ──

    @Test
    fun `DELIVERED reminder has its deliveredAt column populated by the worker`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val reminderId = messageRepository.scheduleReminder(
            userId = "daytime-user", messageId = null,
            title = "Hydrate", scheduledAt = past
        ).getOrNull()!!

        // Pre-condition: row exists in PENDING with no delivered timestamp
        val before = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        assertThat(before.status).isEqualTo(ReminderStatus.PENDING.name)
        assertThat(before.deliveredAt).isNull()

        buildWorker().doWork()

        val after = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        // Only the DELIVERED path writes deliveredAt; SKIPPED paths leave it null.
        // Whichever terminal state was reached, the worker must have mutated
        // the row (not just succeeded silently).
        if (after.status == ReminderStatus.DELIVERED.name) {
            assertThat(after.deliveredAt).isNotNull()
            assertThat(after.deliveredAt).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")
        } else {
            assertThat(after.status).isAnyOf(
                ReminderStatus.SKIPPED_QUIET_HOURS.name,
                ReminderStatus.SKIPPED_CAP_REACHED.name
            )
        }
    }

    @Test
    fun `processing multiple due reminders advances every PENDING row in one pass`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(20).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val ids = listOf("u1", "u2", "u3").map {
            messageRepository.scheduleReminder(
                userId = it, messageId = null, title = "r-$it", scheduledAt = past
            ).getOrNull()!!
        }

        // Pre-condition: all three are PENDING
        val pendingBefore = database.messagesQueries.getPendingReminders(
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).executeAsList()
        assertThat(pendingBefore.map { it.id }).containsAtLeastElementsIn(ids)

        buildWorker().doWork()

        // Post-condition: zero PENDING rows remain among our seeded set
        val pendingAfter = database.messagesQueries.getPendingReminders(
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).executeAsList().map { it.id }
        for (id in ids) assertThat(pendingAfter).doesNotContain(id)
    }
}

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
 * Integration test for [ReminderWorker] with **zero** mocks or spies on
 * the execution path. WorkManager is initialized via
 * [WorkManagerTestInitHelper] with a synchronous executor; every
 * collaborator (repository, use case) is the real production class
 * wrapped around an in-memory SQLDelight database. Assertions observe
 * the real DB rows the worker mutates.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
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
                ): ListenableWorker =
                    ReminderWorker(appContext, workerParameters, messagingUseCase)
            })
            .build()

    @Test
    fun `doWork leaves no pending due reminders in the DB after success`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(20).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val ids = listOf("u1", "u2", "u3").map { userId ->
            messageRepository.scheduleReminder(
                userId = userId, messageId = null, title = "due reminder",
                scheduledAt = past
            ).getOrNull()!!
        }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Every seeded row advanced out of PENDING
        for (id in ids) {
            val row = database.messagesQueries.getReminderById(id).executeAsOne()
            assertThat(row.status).isNotEqualTo(ReminderStatus.PENDING.name)
        }
    }

    @Test
    fun `doWork populates deliveredAt column for reminders that reach DELIVERED`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val reminderId = messageRepository.scheduleReminder(
            userId = "u1", messageId = null, title = "hydrate",
            scheduledAt = past
        ).getOrNull()!!

        buildWorker().doWork()

        val row = database.messagesQueries.getReminderById(reminderId).executeAsOne()
        if (row.status == ReminderStatus.DELIVERED.name) {
            // DELIVERED implies deliveredAt was set by the worker
            assertThat(row.deliveredAt).isNotNull()
        } else {
            // Otherwise a SKIPPED state -- explicit enumeration catches any
            // unexpected new status silently slipping in.
            assertThat(row.status).isAnyOf(
                ReminderStatus.SKIPPED_QUIET_HOURS.name,
                ReminderStatus.SKIPPED_CAP_REACHED.name
            )
        }
    }

    @Test
    fun `doWork ignores reminders scheduled in the future - row stays PENDING`() = runBlocking {
        val future = LocalDateTime.now().plusHours(4).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val id = messageRepository.scheduleReminder(
            "u1", null, "later", future
        ).getOrNull()!!

        buildWorker().doWork()

        val row = database.messagesQueries.getReminderById(id).executeAsOne()
        assertThat(row.status).isEqualTo(ReminderStatus.PENDING.name)
    }

    @Test
    fun `doWork is idempotent - running twice leaves the terminal state unchanged`() = runBlocking {
        val past = LocalDateTime.now().minusMinutes(10).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val id = messageRepository.scheduleReminder("u1", null, "t", past).getOrNull()!!

        buildWorker().doWork()
        val afterFirst = database.messagesQueries.getReminderById(id).executeAsOne()

        buildWorker().doWork()
        val afterSecond = database.messagesQueries.getReminderById(id).executeAsOne()

        assertThat(afterSecond.status).isEqualTo(afterFirst.status)
    }
}

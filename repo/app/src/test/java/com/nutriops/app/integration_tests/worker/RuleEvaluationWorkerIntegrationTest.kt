package com.nutriops.app.integration_tests.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
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
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import com.nutriops.app.worker.RuleEvaluationWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Integration test for [RuleEvaluationWorker] with zero mocks or spies.
 * WorkManager is initialized via [WorkManagerTestInitHelper]; every
 * collaborator is the real production class wrapped around an in-memory
 * SQLDelight database. Post-execution state is asserted against real
 * metric snapshot rows the worker writes back.
 */
@RunWith(RobolectricTestRunner::class)
class RuleEvaluationWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var ruleRepository: RuleRepository
    private lateinit var userRepository: UserRepository
    private lateinit var evaluateRuleUseCase: EvaluateRuleUseCase

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
        ruleRepository = RuleRepository(database, auditManager)
        userRepository = UserRepository(database, auditManager)
        evaluateRuleUseCase = EvaluateRuleUseCase(ruleRepository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun buildWorker(inputData: Data = Data.EMPTY): RuleEvaluationWorker =
        TestListenableWorkerBuilder<RuleEvaluationWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = RuleEvaluationWorker(
                    appContext, workerParameters,
                    evaluateRuleUseCase, ruleRepository, userRepository
                )
            })
            .build()

    private fun seedUser(id: String, isActive: Boolean = true) {
        val now = nowIso()
        database.usersQueries.insertUser(
            id = id, username = id, passwordHash = "x",
            role = "END_USER", isActive = if (isActive) 1L else 0L,
            isLocked = 0, failedLoginAttempts = 0, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )
    }

    private suspend fun seedAdherenceRule(): String = ruleRepository.createRule(
        name = "Adherence", description = "",
        ruleType = "ADHERENCE",
        conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
        hysteresisEnter = 80.0, hysteresisExit = 90.0,
        minimumDurationMinutes = 0,
        effectiveWindowStart = null, effectiveWindowEnd = null,
        actorId = "admin1", actorRole = Role.ADMINISTRATOR
    ).getOrNull()!!

    private fun seedMetric(userId: String, ruleId: String, value: Double) {
        database.metricsSnapshotsQueries.insertMetricsSnapshot(
            id = UUID.randomUUID().toString(),
            userId = userId, ruleId = ruleId,
            metricType = "adherence_rate", metricValue = value,
            snapshotDate = nowIso(), metadata = "{}",
            createdAt = nowIso()
        )
    }

    @Test
    fun `worker writes rule_eval snapshot for each active user with metric data`() = runBlocking {
        seedUser("u-active")
        val ruleId = seedAdherenceRule()
        seedMetric("u-active", ruleId, 90.0)

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val followUps = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u-active").executeAsList()
            .filter { it.metricType == "rule_eval_$ruleId" }
        assertThat(followUps).hasSize(1)
    }

    @Test
    fun `persisted rule_eval snapshot carries triggered flag and reason in metadata`() = runBlocking {
        seedUser("u")
        val ruleId = seedAdherenceRule()
        seedMetric("u", ruleId, 95.0)

        buildWorker().doWork()

        val snapshot = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u").executeAsList()
            .first { it.metricType == "rule_eval_$ruleId" }
        assertThat(snapshot.metadata).contains("\"triggered\":true")
        assertThat(snapshot.metadata).contains("\"reason\"")
    }

    @Test
    fun `inactive users are skipped - no rule_eval snapshot written`() = runBlocking {
        seedUser("u-inactive", isActive = false)
        val ruleId = seedAdherenceRule()
        seedMetric("u-inactive", ruleId, 90.0)

        buildWorker().doWork()

        val followUps = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u-inactive").executeAsList()
            .filter { it.metricType == "rule_eval_$ruleId" }
        assertThat(followUps).isEmpty()
    }

    @Test
    fun `userId input restricts evaluation to that user only`() = runBlocking {
        seedUser("u-target")
        seedUser("u-bystander")
        val ruleId = seedAdherenceRule()
        seedMetric("u-target", ruleId, 90.0)
        seedMetric("u-bystander", ruleId, 90.0)

        val input = Data.Builder()
            .putString("userId", "u-target")
            .putString("actorId", "admin1")
            .build()
        buildWorker(input).doWork()

        val target = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u-target").executeAsList()
            .filter { it.metricType == "rule_eval_$ruleId" }
        val bystander = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u-bystander").executeAsList()
            .filter { it.metricType == "rule_eval_$ruleId" }

        assertThat(target).hasSize(1)
        assertThat(bystander).isEmpty()
    }

    @Test
    fun `running the worker twice appends a second snapshot - evaluations are append-only`() = runBlocking {
        seedUser("u")
        val ruleId = seedAdherenceRule()
        seedMetric("u", ruleId, 85.0)

        buildWorker().doWork()
        buildWorker().doWork()

        val followUps = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u").executeAsList()
            .filter { it.metricType == "rule_eval_$ruleId" }
        assertThat(followUps).hasSize(2)
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

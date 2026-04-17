package com.nutriops.app.unit_tests.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
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

/**
 * Worker test against a real in-memory SQLDelight database, real
 * [RuleRepository], real [UserRepository] and real [EvaluateRuleUseCase].
 * No mocks — the worker's orchestration is exercised end-to-end and we
 * assert the evaluation results were persisted back as metric snapshots.
 */
@RunWith(RobolectricTestRunner::class)
class RuleEvaluationWorkerTest {

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

    @Test
    fun `doWork with no users and no rules completes successfully`(): Unit = runBlocking {
        val result = buildWorker().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `doWork iterates only active users`(): Unit = runBlocking {
        // Seed two active and one inactive user
        insertUser("active-a", isActive = true)
        insertUser("inactive", isActive = false)
        insertUser("active-b", isActive = true)

        // Seed a rule + metric snapshots for each user so evaluateAllRules
        // has something to run against
        val ruleId = createAdherenceRule(minDurationMinutes = 0)
        insertMetric("active-a", ruleId, "adherence_rate", 85.0)
        insertMetric("active-b", ruleId, "adherence_rate", 60.0) // below threshold
        insertMetric("inactive", ruleId, "adherence_rate", 90.0) // should be skipped

        buildWorker().doWork()

        // After a round of evaluation the worker writes back a follow-up
        // metric snapshot (metricType = "rule_eval_${ruleId}") per evaluated
        // user. We assert it appeared for active users only.
        val afterMetrics = database.metricsSnapshotsQueries.getSnapshotsByUser("active-a").executeAsList()
        assertThat(afterMetrics.map { it.metricType }).contains("rule_eval_$ruleId")

        val inactiveAfter = database.metricsSnapshotsQueries.getSnapshotsByUser("inactive").executeAsList()
        assertThat(inactiveAfter.map { it.metricType }).doesNotContain("rule_eval_$ruleId")
    }

    @Test
    fun `doWork evaluates only the specified user when userId input is provided`(): Unit = runBlocking {
        insertUser("target", isActive = true)
        insertUser("bystander", isActive = true)
        val ruleId = createAdherenceRule(minDurationMinutes = 0)
        insertMetric("target", ruleId, "adherence_rate", 90.0)
        insertMetric("bystander", ruleId, "adherence_rate", 90.0)

        val input = Data.Builder()
            .putString("userId", "target")
            .putString("actorId", "admin1")
            .build()
        buildWorker(input).doWork()

        val targetMetrics = database.metricsSnapshotsQueries.getSnapshotsByUser("target").executeAsList()
        val bystanderMetrics = database.metricsSnapshotsQueries.getSnapshotsByUser("bystander").executeAsList()

        assertThat(targetMetrics.map { it.metricType }).contains("rule_eval_$ruleId")
        assertThat(bystanderMetrics.map { it.metricType }).doesNotContain("rule_eval_$ruleId")
    }

    @Test
    fun `doWork skips users with no prior metric snapshots`(): Unit = runBlocking {
        insertUser("user1", isActive = true)
        createAdherenceRule(minDurationMinutes = 0)

        val result = buildWorker().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // No metric snapshots were written back (the user had no metrics to
        // evaluate in the first place).
        val snapshots = database.metricsSnapshotsQueries.getSnapshotsByUser("user1").executeAsList()
        assertThat(snapshots).isEmpty()
    }

    // ── Explicit observable side-effect assertions ──

    @Test
    fun `worker writes exactly one rule_eval snapshot per rule per evaluated user`(): Unit = runBlocking {
        insertUser("u-one", isActive = true)
        val r1 = createAdherenceRule(minDurationMinutes = 0)
        val r2 = createAdherenceRule(minDurationMinutes = 0)
        insertMetric("u-one", r1, "adherence_rate", 90.0)
        insertMetric("u-one", r2, "adherence_rate", 90.0)

        buildWorker().doWork()

        val snapshots = database.metricsSnapshotsQueries.getSnapshotsByUser("u-one").executeAsList()
        // One rule_eval_<id> row per rule (in addition to the two seeded rows)
        val evalRows = snapshots.filter { it.metricType.startsWith("rule_eval_") }
        assertThat(evalRows).hasSize(2)
        assertThat(evalRows.map { it.metricType }.toSet())
            .containsExactly("rule_eval_$r1", "rule_eval_$r2")
    }

    @Test
    fun `persisted rule_eval snapshot carries the triggered flag and reason metadata`(): Unit = runBlocking {
        insertUser("u-triggered", isActive = true)
        val triggeredRule = createAdherenceRule(minDurationMinutes = 0)
        insertMetric("u-triggered", triggeredRule, "adherence_rate", 95.0) // above threshold

        buildWorker().doWork()

        val snapshot = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u-triggered").executeAsList()
            .first { it.metricType == "rule_eval_$triggeredRule" }
        // The worker records triggered=true and a reason string inside metadata
        assertThat(snapshot.metadata).contains("\"triggered\":true")
        assertThat(snapshot.metadata).contains("\"reason\"")
    }

    @Test
    fun `second worker run is idempotent - appends another snapshot, does not mutate the first`(): Unit = runBlocking {
        insertUser("u", isActive = true)
        val ruleId = createAdherenceRule(minDurationMinutes = 0)
        insertMetric("u", ruleId, "adherence_rate", 85.0)

        buildWorker().doWork()
        val afterFirstRun = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u").executeAsList()
            .count { it.metricType == "rule_eval_$ruleId" }

        buildWorker().doWork()
        val afterSecondRun = database.metricsSnapshotsQueries
            .getSnapshotsByUser("u").executeAsList()
            .count { it.metricType == "rule_eval_$ruleId" }

        // Each run appends a new snapshot -- append-only audit of evaluations
        assertThat(afterFirstRun).isEqualTo(1)
        assertThat(afterSecondRun).isEqualTo(2)
    }

    // ── helpers ──

    private fun insertUser(id: String, isActive: Boolean) {
        val now = nowIso()
        database.usersQueries.insertUser(
            id = id, username = id, passwordHash = "x",
            role = "END_USER", isActive = if (isActive) 1L else 0L,
            isLocked = 0, failedLoginAttempts = 0, lockoutUntil = null,
            createdAt = now, updatedAt = now
        )
    }

    private suspend fun createAdherenceRule(minDurationMinutes: Long): String = ruleRepository.createRule(
        name = "Adherence threshold",
        description = "",
        ruleType = "ADHERENCE",
        conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
        hysteresisEnter = 80.0, hysteresisExit = 90.0,
        minimumDurationMinutes = minDurationMinutes,
        effectiveWindowStart = null, effectiveWindowEnd = null,
        actorId = "admin1", actorRole = Role.ADMINISTRATOR
    ).getOrNull()!!

    private fun insertMetric(
        userId: String, ruleId: String, metricType: String, value: Double,
        snapshotDate: String = nowIso()
    ) {
        database.metricsSnapshotsQueries.insertMetricsSnapshot(
            id = java.util.UUID.randomUUID().toString(),
            userId = userId, ruleId = ruleId,
            metricType = metricType, metricValue = value,
            snapshotDate = snapshotDate, metadata = "{}",
            createdAt = nowIso()
        )
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

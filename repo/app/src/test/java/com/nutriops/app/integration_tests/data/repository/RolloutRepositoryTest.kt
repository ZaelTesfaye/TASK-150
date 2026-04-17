package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.RolloutStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RolloutRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: RolloutRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = RolloutRepository(database, auditManager)

        // Seed some end users so the canary assignment has a non-empty
        // candidate pool to compute deterministic membership from.
        seedUsers("u-alpha", "u-bravo", "u-charlie", "u-delta", "u-echo")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── createRollout ──

    @Test
    fun `createRollout persists canary assignment and status`() = runBlocking {
        val rolloutId = repository.createRollout(
            configVersionId = "v1", canaryPercentage = 20,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()!!

        val rollout = repository.getRolloutById(rolloutId)
        assertThat(rollout).isNotNull()
        assertThat(rollout!!.status).isEqualTo(RolloutStatus.CANARY.name)
        assertThat(rollout.canaryPercentage).isEqualTo(20L)
        assertThat(rollout.totalUsers).isEqualTo(5L)
        // 20% of 5 = 1 — deterministic: alphabetically-first user ("u-alpha")
        assertThat(rollout.canaryUsers).contains("u-alpha")

        val audit = auditManager.getAuditTrail("Rollout", rolloutId)
        assertThat(audit.first().action).isEqualTo(AuditAction.ROLLOUT_START.name)
    }

    @Test
    fun `canary assignment is deterministic across repeated calls`() = runBlocking {
        val first = repository.createRollout("v1", 40, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        val second = repository.createRollout("v2", 40, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val firstRollout = repository.getRolloutById(first)!!
        val secondRollout = repository.getRolloutById(second)!!

        assertThat(firstRollout.canaryUsers).isEqualTo(secondRollout.canaryUsers)
    }

    @Test
    fun `isUserInCanary reflects stored canary membership`() = runBlocking {
        // 40% of 5 users = 2 — alphabetically first two are u-alpha and u-bravo
        val rolloutId = repository.createRollout("v1", 40, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        assertThat(repository.isUserInCanary("u-alpha", rolloutId)).isTrue()
        assertThat(repository.isUserInCanary("u-bravo", rolloutId)).isTrue()
        assertThat(repository.isUserInCanary("u-charlie", rolloutId)).isFalse()
        assertThat(repository.isUserInCanary("nobody", rolloutId)).isFalse()
    }

    // ── promoteToFull ──

    @Test
    fun `promoteToFull transitions CANARY to FULL`() = runBlocking {
        val rolloutId = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val result = repository.promoteToFull(rolloutId, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        val rollout = repository.getRolloutById(rolloutId)!!
        assertThat(rollout.status).isEqualTo(RolloutStatus.FULL.name)
    }

    @Test
    fun `promoteToFull fails for unknown rollout id`() = runBlocking {
        val result = repository.promoteToFull("missing", "admin1", Role.ADMINISTRATOR)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `promoteToFull is rejected when rollout is already FULL`() = runBlocking {
        val rolloutId = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.promoteToFull(rolloutId, "admin1", Role.ADMINISTRATOR)

        val again = repository.promoteToFull(rolloutId, "admin1", Role.ADMINISTRATOR)
        assertThat(again.isFailure).isTrue()
        assertThat(again.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    // ── rollback ──

    @Test
    fun `rollback transitions rollout to ROLLED_BACK`() = runBlocking {
        val rolloutId = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val result = repository.rollback(rolloutId, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        val rollout = repository.getRolloutById(rolloutId)!!
        assertThat(rollout.status).isEqualTo(RolloutStatus.ROLLED_BACK.name)
    }

    @Test
    fun `rollback of an already rolled-back rollout is idempotent`() = runBlocking {
        val rolloutId = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.rollback(rolloutId, "admin1", Role.ADMINISTRATOR)

        val second = repository.rollback(rolloutId, "admin1", Role.ADMINISTRATOR)
        assertThat(second.isSuccess).isTrue()

        val rollout = repository.getRolloutById(rolloutId)!!
        assertThat(rollout.status).isEqualTo(RolloutStatus.ROLLED_BACK.name)
    }

    // ── getActiveRollout / filtering by status ──

    @Test
    fun `getActiveRollout returns the most recent CANARY or FULL rollout`() = runBlocking {
        val first = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.rollback(first, "admin1", Role.ADMINISTRATOR) // not active anymore

        val second = repository.createRollout("v2", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!

        val active = repository.getActiveRollout()
        assertThat(active).isNotNull()
        assertThat(active!!.id).isEqualTo(second)
    }

    @Test
    fun `getActiveRollout returns null when all rollouts are rolled back`() = runBlocking {
        val id = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.rollback(id, "admin1", Role.ADMINISTRATOR)

        assertThat(repository.getActiveRollout()).isNull()
    }

    @Test
    fun `getRolloutsByStatus filters correctly via underlying query`() = runBlocking {
        val canaryId = repository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        val fullId = repository.createRollout("v2", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.promoteToFull(fullId, "admin1", Role.ADMINISTRATOR)
        val rbId = repository.createRollout("v3", 10, "admin1", Role.ADMINISTRATOR).getOrNull()!!
        repository.rollback(rbId, "admin1", Role.ADMINISTRATOR)

        val canaryOnly = database.rolloutsQueries.getRolloutsByStatus("CANARY").executeAsList()
        val fullOnly = database.rolloutsQueries.getRolloutsByStatus("FULL").executeAsList()
        val rolledBackOnly = database.rolloutsQueries.getRolloutsByStatus("ROLLED_BACK").executeAsList()

        assertThat(canaryOnly.map { it.id }).containsExactly(canaryId)
        assertThat(fullOnly.map { it.id }).containsExactly(fullId)
        assertThat(rolledBackOnly.map { it.id }).containsExactly(rbId)
    }

    @Test
    fun `inserting a rollout with canaryPercentage out of 1-100 range fails the CHECK`() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val failure = runCatching {
            database.rolloutsQueries.insertRollout(
                id = id, configVersionId = "v1",
                canaryPercentage = 150L, // exceeds CHECK(1..100)
                status = RolloutStatus.CANARY.name,
                totalUsers = 5L, canaryUsers = "[]",
                createdBy = "admin1", createdAt = now, updatedAt = now
            )
        }
        assertThat(failure.isFailure).isTrue()
    }

    // ── helpers ──

    private fun seedUsers(vararg ids: String) {
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        for (id in ids) {
            database.usersQueries.insertUser(
                id = id, username = id, passwordHash = "x",
                role = "END_USER", isActive = 1, isLocked = 0,
                failedLoginAttempts = 0, lockoutUntil = null,
                createdAt = now, updatedAt = now
            )
        }
    }
}

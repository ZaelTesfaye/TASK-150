package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests for [ConfigRepository] backed by a real
 * in-memory SQLDelight database.
 *
 * Note: the repository exposes no `deleteConfig` API — configs are versioned
 * append-only, so the "delete removes it" contract from the audit spec is
 * replaced by a "new version supersedes old value" test that exercises the
 * same write-and-read-back behavior.
 */
class ConfigRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var repository: ConfigRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        repository = ConfigRepository(database, auditManager)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Config CRUD ──

    @Test
    fun `create and retrieve by key returns equal data`() = runBlocking {
        val result = repository.createConfig("feature.x", "on", "admin1", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
        val configId = result.getOrNull()!!

        val stored = repository.getConfigByKey("feature.x")
        assertThat(stored).isNotNull()
        assertThat(stored!!.id).isEqualTo(configId)
        assertThat(stored.configKey).isEqualTo("feature.x")
        assertThat(stored.configValue).isEqualTo("on")
        assertThat(stored.version).isEqualTo(1L)

        val audit = auditManager.getAuditTrail("Config", configId)
        assertThat(audit).hasSize(1)
        assertThat(audit.first().action).isEqualTo(AuditAction.CREATE.name)
    }

    @Test
    fun `update persists new value and bumps version without affecting other keys`() = runBlocking {
        val aId = repository.createConfig("a", "1", "admin1", Role.ADMINISTRATOR).getOrNull()!!
        val bId = repository.createConfig("b", "2", "admin1", Role.ADMINISTRATOR).getOrNull()!!

        repository.updateConfig(aId, "1-updated", "admin1", Role.ADMINISTRATOR)

        val a = repository.getConfigByKey("a")!!
        val b = repository.getConfigByKey("b")!!
        assertThat(a.configValue).isEqualTo("1-updated")
        assertThat(a.version).isEqualTo(2L)
        assertThat(b.configValue).isEqualTo("2")
        assertThat(b.version).isEqualTo(1L)
        assertThat(b.id).isEqualTo(bId)
    }

    @Test
    fun `update preserves a version history row per edit`() = runBlocking {
        val id = repository.createConfig("feature.x", "v1", "admin1", Role.ADMINISTRATOR).getOrNull()!!

        repository.updateConfig(id, "v2", "admin1", Role.ADMINISTRATOR)
        repository.updateConfig(id, "v3", "admin1", Role.ADMINISTRATOR)

        val versions = repository.getConfigVersions(id)
        assertThat(versions).hasSize(3)
        assertThat(versions.map { it.configValue }).containsExactly("v3", "v2", "v1").inOrder()
    }

    @Test
    fun `update fails for unknown config id`() = runBlocking {
        val result = repository.updateConfig("missing", "value", "admin1", Role.ADMINISTRATOR)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `fetching a non-existent key returns null and does not throw`() = runBlocking {
        val result = repository.getConfigByKey("never.existed")
        assertThat(result).isNull()
    }

    @Test
    fun `bulk insert of multiple configs is retrievable via getAllConfigs`() = runBlocking {
        val keys = listOf("k1", "k2", "k3", "k4", "k5")
        for ((idx, key) in keys.withIndex()) {
            repository.createConfig(key, "value-$idx", "admin1", Role.ADMINISTRATOR)
        }

        val all = repository.getAllConfigs()
        assertThat(all).hasSize(5)
        assertThat(all.map { it.configKey }).containsExactlyElementsIn(keys)
    }

    // ── Coupons ──

    @Test
    fun `duplicate coupon code violates UNIQUE constraint on Coupons_code`() = runBlocking {
        val first = repository.createCoupon(
            code = "SAVE10", description = "10% off", discountType = "PERCENT",
            discountValue = 10.0, conditionsJson = "{}",
            maxUsesPerUser = 2L, periodDays = 30L, configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(first.isSuccess).isTrue()

        val duplicate = repository.createCoupon(
            code = "SAVE10", description = "dup", discountType = "PERCENT",
            discountValue = 5.0, conditionsJson = "{}",
            maxUsesPerUser = 1L, periodDays = 30L, configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )

        assertThat(duplicate.isFailure).isTrue()
    }

    @Test
    fun `validateCouponUsage returns true until maxUsesPerUser is reached`() = runBlocking {
        val couponId = repository.createCoupon(
            code = "WELCOME", description = "Welcome discount", discountType = "PERCENT",
            discountValue = 5.0, conditionsJson = "{}",
            maxUsesPerUser = 2L, periodDays = 30L, configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        ).getOrNull()!!

        assertThat(repository.validateCouponUsage(couponId, "u1").getOrNull()).isTrue()
        repository.recordCouponUsage(couponId, "u1")
        assertThat(repository.validateCouponUsage(couponId, "u1").getOrNull()).isTrue()
        repository.recordCouponUsage(couponId, "u1")
        assertThat(repository.validateCouponUsage(couponId, "u1").getOrNull()).isFalse()
    }

    @Test
    fun `validateCouponUsage returns failure for unknown coupon id`() = runBlocking {
        val result = repository.validateCouponUsage("missing", "u1")
        assertThat(result.isFailure).isTrue()
    }

    // ── Black/White list ──

    @Test
    fun `blacklist membership is detectable via isBlacklisted`() = runBlocking {
        repository.addToList("BLACK", "USER", "spam@example.com", "spam",
            "admin1", Role.ADMINISTRATOR)

        assertThat(repository.isBlacklisted("USER", "spam@example.com")).isTrue()
        assertThat(repository.isBlacklisted("USER", "clean@example.com")).isFalse()
        // Whitelist should not match blacklist entries
        assertThat(repository.isWhitelisted("USER", "spam@example.com")).isFalse()
    }

    @Test
    fun `getBlackWhiteList filters by list type`() = runBlocking {
        repository.addToList("BLACK", "USER", "a", "r", "admin1", Role.ADMINISTRATOR)
        repository.addToList("BLACK", "USER", "b", "r", "admin1", Role.ADMINISTRATOR)
        repository.addToList("WHITE", "USER", "c", "r", "admin1", Role.ADMINISTRATOR)

        assertThat(repository.getBlackWhiteList("BLACK")).hasSize(2)
        assertThat(repository.getBlackWhiteList("WHITE")).hasSize(1)
    }

    // ── Homepage / Ad slots / Campaigns ──

    @Test
    fun `createHomepageModule and createAdSlot persist and are retrievable`() = runBlocking {
        repository.createHomepageModule("hero", "HERO", 0L, null, "admin1", Role.ADMINISTRATOR)
        repository.createAdSlot("slot1", "TOP", "payload", null, "admin1", Role.ADMINISTRATOR)

        val modules = repository.getAllHomepageModules()
        val ads = repository.getAllAdSlots()

        assertThat(modules).hasSize(1)
        assertThat(modules.first().name).isEqualTo("hero")
        assertThat(ads).hasSize(1)
        assertThat(ads.first().name).isEqualTo("slot1")
    }

    @Test
    fun `createCampaign rejects end-date-before-start-date`() = runBlocking {
        val result = repository.createCampaign(
            name = "bad", description = "", landingTopic = "topic",
            startDate = "2026-05-01", endDate = "2026-04-01", configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createCampaign rejects malformed date format`() = runBlocking {
        val result = repository.createCampaign(
            name = "bad-date", description = "", landingTopic = "topic",
            startDate = "yesterday", endDate = "tomorrow", configVersionId = null,
            actorId = "admin1", actorRole = Role.ADMINISTRATOR
        )
        assertThat(result.isFailure).isTrue()
    }
}

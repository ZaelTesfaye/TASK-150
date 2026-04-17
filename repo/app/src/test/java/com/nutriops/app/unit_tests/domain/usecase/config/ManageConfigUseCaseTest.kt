package com.nutriops.app.unit_tests.domain.usecase.config

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ManageConfigUseCaseTest {

    private lateinit var configRepository: ConfigRepository
    private lateinit var rolloutRepository: RolloutRepository
    private lateinit var useCase: ManageConfigUseCase

    @Before
    fun setup() {
        configRepository = mockk(relaxed = true)
        rolloutRepository = mockk(relaxed = true)
        useCase = ManageConfigUseCase(configRepository, rolloutRepository)
    }

    // ── createConfig ──

    @Test
    fun `createConfig succeeds for admin`() = runBlocking {
        coEvery {
            configRepository.createConfig("feature.x", "on", "admin1", Role.ADMINISTRATOR)
        } returns Result.success("cfg1")

        val result = useCase.createConfig("feature.x", "on", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("cfg1")
    }

    @Test
    fun `createConfig denied for agent`() = runBlocking {
        val result = useCase.createConfig("feature.x", "on", "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { configRepository.createConfig(any(), any(), any(), any()) }
    }

    @Test
    fun `createConfig denied for end user`() = runBlocking {
        val result = useCase.createConfig("feature.x", "on", "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    // ── updateConfig ──

    @Test
    fun `updateConfig delegates to repo for admin`() = runBlocking {
        coEvery {
            configRepository.updateConfig("cfg1", "off", "admin1", Role.ADMINISTRATOR)
        } returns Result.success(Unit)

        val result = useCase.updateConfig("cfg1", "off", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        coVerify { configRepository.updateConfig("cfg1", "off", "admin1", Role.ADMINISTRATOR) }
    }

    // ── getAllConfigs ──

    @Test
    fun `getAllConfigs succeeds for admin`() = runBlocking {
        coEvery { configRepository.getAllConfigs() } returns emptyList()

        val result = useCase.getAllConfigs(Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `getAllConfigs denied for end user`() = runBlocking {
        val result = useCase.getAllConfigs(Role.END_USER)
        assertThat(result.isFailure).isTrue()
    }

    // ── Homepage / Ad / Campaigns / Coupons ──

    @Test
    fun `createHomepageModule denied for agent`() = runBlocking {
        val result = useCase.createHomepageModule(
            name = "hero", moduleType = "HERO", position = 0L,
            configVersionId = null, actorId = "agent1", actorRole = Role.AGENT
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createAdSlot delegates to repo for admin`() = runBlocking {
        coEvery {
            configRepository.createAdSlot("slot1", "TOP", "payload", null, "admin1", Role.ADMINISTRATOR)
        } returns Result.success("ad1")

        val result = useCase.createAdSlot("slot1", "TOP", "payload", null, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("ad1")
    }

    @Test
    fun `createCampaign denied for non-admin`() = runBlocking {
        val result = useCase.createCampaign(
            name = "camp", description = "d", landingTopic = "topic",
            startDate = "2026-01-01", endDate = "2026-02-01", configVersionId = null,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `createCoupon delegates to repo for admin`() = runBlocking {
        coEvery {
            configRepository.createCoupon(
                "CODE", "desc", "PERCENT", 10.0, "{}",
                2L, 30L, null, "admin1", Role.ADMINISTRATOR
            )
        } returns Result.success("coupon1")

        val result = useCase.createCoupon(
            "CODE", "desc", "PERCENT", 10.0, "{}",
            2L, 30L, null, "admin1", Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
    }

    // ── validateAndUseCoupon ──

    @Test
    fun `validateAndUseCoupon succeeds when limit not reached`() = runBlocking {
        coEvery { configRepository.validateCouponUsage("c1", "u1") } returns Result.success(true)
        coEvery { configRepository.recordCouponUsage("c1", "u1") } just Runs

        val result = useCase.validateAndUseCoupon("c1", "u1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
        coVerify { configRepository.recordCouponUsage("c1", "u1") }
    }

    @Test
    fun `validateAndUseCoupon fails when usage limit reached`() = runBlocking {
        coEvery { configRepository.validateCouponUsage("c1", "u1") } returns Result.success(false)

        val result = useCase.validateAndUseCoupon("c1", "u1")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { configRepository.recordCouponUsage(any(), any()) }
    }

    @Test
    fun `validateAndUseCoupon propagates repository validation failure`() = runBlocking {
        coEvery { configRepository.validateCouponUsage("c1", "u1") } returns
            Result.failure(IllegalStateException("not found"))

        val result = useCase.validateAndUseCoupon("c1", "u1")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { configRepository.recordCouponUsage(any(), any()) }
    }

    // ── Black/Whitelist ──

    @Test
    fun `addToBlacklist routes to BLACK list`() = runBlocking {
        coEvery {
            configRepository.addToList("BLACK", "USER", "bad@x.com", "spam", "admin1", Role.ADMINISTRATOR)
        } returns Result.success("bl1")

        val result = useCase.addToBlacklist("USER", "bad@x.com", "spam", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `addToWhitelist routes to WHITE list`() = runBlocking {
        coEvery {
            configRepository.addToList("WHITE", "USER", "good@x.com", "vip", "admin1", Role.ADMINISTRATOR)
        } returns Result.success("wl1")

        val result = useCase.addToWhitelist("USER", "good@x.com", "vip", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `isBlacklisted delegates to repo for admin`() = runBlocking {
        coEvery { configRepository.isBlacklisted("USER", "bad@x.com") } returns true

        val result = useCase.isBlacklisted("USER", "bad@x.com", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }

    // ── Canary Rollout ──

    @Test
    fun `startCanaryRollout denied for agent`() = runBlocking {
        val result = useCase.startCanaryRollout("v1", 10, "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { rolloutRepository.createRollout(any(), any(), any(), any()) }
    }

    @Test
    fun `startCanaryRollout delegates to repo for admin`() = runBlocking {
        coEvery {
            rolloutRepository.createRollout("v1", 10, "admin1", Role.ADMINISTRATOR)
        } returns Result.success("rollout1")

        val result = useCase.startCanaryRollout("v1", 10, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("rollout1")
    }

    @Test
    fun `promoteRollout delegates to repo for admin`() = runBlocking {
        coEvery {
            rolloutRepository.promoteToFull("r1", "admin1", Role.ADMINISTRATOR)
        } returns Result.success(Unit)

        val result = useCase.promoteRollout("r1", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `rollbackRollout delegates to repo for admin`() = runBlocking {
        coEvery {
            rolloutRepository.rollback("r1", "admin1", Role.ADMINISTRATOR)
        } returns Result.success(Unit)

        val result = useCase.rollbackRollout("r1", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `isUserInCanary returns repo result for admin`() = runBlocking {
        every { rolloutRepository.isUserInCanary("u1", "r1") } returns true

        val result = useCase.isUserInCanary("u1", "r1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }
}

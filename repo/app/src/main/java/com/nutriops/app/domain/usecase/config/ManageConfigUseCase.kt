package com.nutriops.app.domain.usecase.config

import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

/**
 * Operations & Configuration Center use case:
 * - Homepage modules, ad slots, campaigns, coupons
 * - Black/whitelist, purchase limits
 * - Config versioning
 * - 10% canary rollout (deterministic assignment)
 */
class ManageConfigUseCase @Inject constructor(
    private val configRepository: ConfigRepository,
    private val rolloutRepository: RolloutRepository
) {
    // ── Config ──

    suspend fun createConfig(key: String, value: String, actorId: String, actorRole: Role): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CONFIG)
            .getOrElse { return Result.failure(it) }
        return configRepository.createConfig(key, value, actorId, actorRole)
    }

    suspend fun updateConfig(configId: String, newValue: String, actorId: String, actorRole: Role): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CONFIG)
            .getOrElse { return Result.failure(it) }
        return configRepository.updateConfig(configId, newValue, actorId, actorRole)
    }

    suspend fun getAllConfigs(actorRole: Role): Result<List<com.nutriops.app.data.local.Configs>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CONFIG)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getAllConfigs())
    }

    suspend fun getConfigVersions(configId: String, actorRole: Role): Result<List<com.nutriops.app.data.local.ConfigVersions>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CONFIG)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getConfigVersions(configId))
    }

    // ── Homepage Modules ──

    suspend fun createHomepageModule(
        name: String, moduleType: String, position: Long, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_HOMEPAGE_MODULES)
            .getOrElse { return Result.failure(it) }
        return configRepository.createHomepageModule(name, moduleType, position, configVersionId, actorId, actorRole)
    }

    suspend fun getAllHomepageModules(actorRole: Role): Result<List<com.nutriops.app.data.local.HomepageModules>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_HOMEPAGE_MODULES)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getAllHomepageModules())
    }

    // ── Ad Slots ──

    suspend fun createAdSlot(
        name: String, position: String, content: String, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_AD_SLOTS)
            .getOrElse { return Result.failure(it) }
        return configRepository.createAdSlot(name, position, content, configVersionId, actorId, actorRole)
    }

    suspend fun getAllAdSlots(actorRole: Role): Result<List<com.nutriops.app.data.local.AdSlots>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_AD_SLOTS)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getAllAdSlots())
    }

    // ── Campaigns ──

    suspend fun createCampaign(
        name: String, description: String, landingTopic: String,
        startDate: String, endDate: String, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CAMPAIGNS)
            .getOrElse { return Result.failure(it) }
        return configRepository.createCampaign(name, description, landingTopic, startDate, endDate, configVersionId, actorId, actorRole)
    }

    suspend fun getAllCampaigns(actorRole: Role): Result<List<com.nutriops.app.data.local.Campaigns>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_CAMPAIGNS)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getAllCampaigns())
    }

    // ── Coupons ──

    suspend fun createCoupon(
        code: String, description: String, discountType: String, discountValue: Double,
        conditionsJson: String, maxUsesPerUser: Long, periodDays: Long,
        configVersionId: String?, actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_COUPONS)
            .getOrElse { return Result.failure(it) }
        return configRepository.createCoupon(
            code, description, discountType, discountValue, conditionsJson,
            maxUsesPerUser, periodDays, configVersionId, actorId, actorRole
        )
    }

    suspend fun validateAndUseCoupon(couponId: String, userId: String): Result<Boolean> {
        val valid = configRepository.validateCouponUsage(couponId, userId).getOrElse { return Result.failure(it) }
        if (!valid) return Result.failure(IllegalStateException("Coupon usage limit reached"))
        configRepository.recordCouponUsage(couponId, userId)
        return Result.success(true)
    }

    suspend fun getAllCoupons(actorRole: Role): Result<List<com.nutriops.app.data.local.Coupons>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_COUPONS)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getAllCoupons())
    }

    // ── Black/White List ──

    suspend fun addToBlacklist(
        entityType: String, entityValue: String, reason: String,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_BLACKWHITELIST)
            .getOrElse { return Result.failure(it) }
        return configRepository.addToList("BLACK", entityType, entityValue, reason, actorId, actorRole)
    }

    suspend fun addToWhitelist(
        entityType: String, entityValue: String, reason: String,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_BLACKWHITELIST)
            .getOrElse { return Result.failure(it) }
        return configRepository.addToList("WHITE", entityType, entityValue, reason, actorId, actorRole)
    }

    suspend fun isBlacklisted(entityType: String, entityValue: String, actorRole: Role): Result<Boolean> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_BLACKWHITELIST)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.isBlacklisted(entityType, entityValue))
    }

    suspend fun getBlacklist(actorRole: Role): Result<List<com.nutriops.app.data.local.BlackWhiteList>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_BLACKWHITELIST)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getBlackWhiteList("BLACK"))
    }

    suspend fun getWhitelist(actorRole: Role): Result<List<com.nutriops.app.data.local.BlackWhiteList>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_BLACKWHITELIST)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getBlackWhiteList("WHITE"))
    }

    // ── Purchase Limits ──

    suspend fun setPurchaseLimit(
        entityType: String, maxQuantity: Long, periodDays: Long,
        actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_PURCHASE_LIMITS)
            .getOrElse { return Result.failure(it) }
        return configRepository.setPurchaseLimit(entityType, maxQuantity, periodDays, actorId, actorRole)
    }

    suspend fun getPurchaseLimits(actorRole: Role): Result<List<com.nutriops.app.data.local.PurchaseLimits>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_PURCHASE_LIMITS)
            .getOrElse { return Result.failure(it) }
        return Result.success(configRepository.getPurchaseLimits())
    }

    // ── Canary Rollout ──

    suspend fun startCanaryRollout(
        configVersionId: String, canaryPercentage: Int, actorId: String, actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_ROLLOUTS)
            .getOrElse { return Result.failure(it) }
        return rolloutRepository.createRollout(configVersionId, canaryPercentage, actorId, actorRole)
    }

    suspend fun promoteRollout(rolloutId: String, actorId: String, actorRole: Role): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_ROLLOUTS)
            .getOrElse { return Result.failure(it) }
        return rolloutRepository.promoteToFull(rolloutId, actorId, actorRole)
    }

    suspend fun rollbackRollout(rolloutId: String, actorId: String, actorRole: Role): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_ROLLOUTS)
            .getOrElse { return Result.failure(it) }
        return rolloutRepository.rollback(rolloutId, actorId, actorRole)
    }

    suspend fun getActiveRollout(actorRole: Role): Result<com.nutriops.app.data.local.Rollouts?> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_ROLLOUTS)
            .getOrElse { return Result.failure(it) }
        return Result.success(rolloutRepository.getActiveRollout())
    }

    fun isUserInCanary(userId: String, rolloutId: String, actorRole: Role): Result<Boolean> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_ROLLOUTS)
            .getOrElse { return Result.failure(it) }
        return Result.success(rolloutRepository.isUserInCanary(userId, rolloutId))
    }
}

package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val configQueries get() = database.configsQueries

    // ── Config CRUD with versioning ──

    suspend fun createConfig(
        key: String, value: String, actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val configId = UUID.randomUUID().toString()
            val versionId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Config", entityId = configId,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"key":"$key","version":1}"""
            ) {
                configQueries.insertConfig(configId, key, value, 1, 1, actorId, now, now)
                configQueries.insertConfigVersion(versionId, configId, 1, value, actorId, now)
            }

            Result.success(configId)
        } catch (e: Exception) {
            AppLogger.error("ConfigRepo", "Failed to create config", e)
            Result.failure(e)
        }
    }

    suspend fun updateConfig(
        configId: String, newValue: String, actorId: String, actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val config = configQueries.getConfigById(configId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Config not found"))

            val newVersion = config.version + 1
            val versionId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Config", entityId = configId,
                action = AuditAction.CONFIG_CHANGE, actorId = actorId, actorRole = actorRole,
                previousState = """{"version":${config.version},"value":"${config.configValue}"}""",
                newState = """{"version":$newVersion,"value":"$newValue"}"""
            ) {
                configQueries.updateConfig(newValue, newVersion, 1, now, configId)
                configQueries.insertConfigVersion(versionId, configId, newVersion, newValue, actorId, now)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("ConfigRepo", "Failed to update config", e)
            Result.failure(e)
        }
    }

    suspend fun getConfigByKey(key: String) = withContext(Dispatchers.IO) {
        configQueries.getConfigByKey(key).executeAsOneOrNull()
    }

    suspend fun getAllConfigs() = withContext(Dispatchers.IO) {
        configQueries.getAllConfigs().executeAsList()
    }

    suspend fun getConfigVersions(configId: String) = withContext(Dispatchers.IO) {
        configQueries.getConfigVersions(configId).executeAsList()
    }

    // ── Homepage Modules ──

    suspend fun createHomepageModule(
        name: String, moduleType: String, position: Long, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "HomepageModule", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"name":"$name","type":"$moduleType","position":$position}"""
            ) {
                configQueries.insertHomepageModule(id, name, moduleType, position, 1, configVersionId, now, now)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllHomepageModules() = withContext(Dispatchers.IO) {
        configQueries.getAllHomepageModules().executeAsList()
    }

    // ── Ad Slots ──

    suspend fun createAdSlot(
        name: String, position: String, content: String, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "AdSlot", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"name":"$name","position":"$position"}"""
            ) {
                configQueries.insertAdSlot(id, name, position, content, 1, configVersionId, now, now)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllAdSlots() = withContext(Dispatchers.IO) {
        configQueries.getAllAdSlots().executeAsList()
    }

    // ── Campaigns ──

    suspend fun createCampaign(
        name: String, description: String, landingTopic: String,
        startDate: String, endDate: String, configVersionId: String?,
        actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            try {
                val start = LocalDate.parse(startDate)
                val end = LocalDate.parse(endDate)
                if (end.isBefore(start)) {
                    return@withContext Result.failure(IllegalArgumentException("End date before start date"))
                }
            } catch (e: java.time.format.DateTimeParseException) {
                return@withContext Result.failure(IllegalArgumentException("Invalid date format: use YYYY-MM-DD"))
            }
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Campaign", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"name":"$name","topic":"$landingTopic"}"""
            ) {
                configQueries.insertCampaign(id, name, description, landingTopic, startDate, endDate, 1, configVersionId, now, now)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllCampaigns() = withContext(Dispatchers.IO) {
        configQueries.getAllCampaigns().executeAsList()
    }

    // ── Coupons ──

    suspend fun createCoupon(
        code: String, description: String, discountType: String, discountValue: Double,
        conditionsJson: String, maxUsesPerUser: Long, periodDays: Long,
        configVersionId: String?, actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Coupon", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"code":"$code","discountType":"$discountType","value":$discountValue}"""
            ) {
                configQueries.insertCoupon(
                    id, code, description, discountType, discountValue, conditionsJson,
                    maxUsesPerUser, periodDays, 1, configVersionId, now, now
                )
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateCouponUsage(couponId: String, userId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val coupon = configQueries.getCouponById(couponId).executeAsOneOrNull()
                    ?: return@withContext Result.failure(IllegalArgumentException("Coupon not found"))

                val periodStart = LocalDateTime.now()
                    .minusDays(coupon.periodDays)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                val usageCount = configQueries.getCouponUsageCount(couponId, userId, periodStart).executeAsOne()

                Result.success(usageCount < coupon.maxUsesPerUser)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun recordCouponUsage(couponId: String, userId: String) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        configQueries.insertCouponUsage(id, couponId, userId, now)
    }

    suspend fun getAllCoupons() = withContext(Dispatchers.IO) {
        configQueries.getAllCoupons().executeAsList()
    }

    // ── Black/White List ──

    suspend fun addToList(
        listType: String, entityType: String, entityValue: String, reason: String,
        actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "BlackWhiteList", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"listType":"$listType","entityType":"$entityType","value":"$entityValue"}"""
            ) {
                configQueries.insertBlackWhiteListEntry(id, listType, entityType, entityValue, reason, 1, actorId, now, now)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isBlacklisted(entityType: String, entityValue: String) = withContext(Dispatchers.IO) {
        configQueries.isBlacklisted(entityType, entityValue).executeAsOne() > 0
    }

    suspend fun isWhitelisted(entityType: String, entityValue: String) = withContext(Dispatchers.IO) {
        configQueries.isWhitelisted(entityType, entityValue).executeAsOne() > 0
    }

    suspend fun getBlackWhiteList(listType: String) = withContext(Dispatchers.IO) {
        configQueries.getBlackWhiteList(listType).executeAsList()
    }

    // ── Purchase Limits ──

    suspend fun setPurchaseLimit(
        entityType: String, maxQuantity: Long, periodDays: Long,
        actorId: String, actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "PurchaseLimit", entityId = id,
                action = AuditAction.CREATE, actorId = actorId, actorRole = actorRole,
                details = """{"entityType":"$entityType","max":$maxQuantity,"periodDays":$periodDays}"""
            ) {
                configQueries.insertPurchaseLimit(id, entityType, maxQuantity, periodDays, 1, actorId, now, now)
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPurchaseLimits() = withContext(Dispatchers.IO) {
        configQueries.getPurchaseLimits().executeAsList()
    }

    suspend fun getPurchaseLimitByType(entityType: String) = withContext(Dispatchers.IO) {
        configQueries.getPurchaseLimitByType(entityType).executeAsOneOrNull()
    }
}

package com.nutriops.app.config

import android.content.Context
import com.nutriops.app.logging.AppLogger
import com.nutriops.app.security.DatabaseKeyManager

/**
 * Single source of truth for all application configuration.
 * All configuration values are centralized here — application logic must never
 * access system properties or environment variables directly.
 */
object AppConfig {

    // Database
    const val DB_NAME = "nutriops.db"
    val DB_ENCRYPTION_KEY: String
        get() = configValues["DB_ENCRYPTION_KEY"]
            ?: throw IllegalStateException("Database encryption key not initialized. Call AppConfig.initialize() first.")

    // TLS Toggle (per guide requirement)
    val ENABLE_TLS: Boolean
        get() = configValues["ENABLE_TLS"]?.toBooleanStrictOrNull() ?: false

    // Authentication
    val MAX_LOGIN_ATTEMPTS: Int
        get() = configValues["MAX_LOGIN_ATTEMPTS"]?.toIntOrNull() ?: 5
    val LOCKOUT_DURATION_MINUTES: Long
        get() = configValues["LOCKOUT_DURATION_MINUTES"]?.toLongOrNull() ?: 30L

    // SLA Configuration
    val SLA_FIRST_RESPONSE_HOURS: Int
        get() = configValues["SLA_FIRST_RESPONSE_HOURS"]?.toIntOrNull() ?: 4
    val SLA_RESOLUTION_DAYS: Int
        get() = configValues["SLA_RESOLUTION_DAYS"]?.toIntOrNull() ?: 3

    // Messaging / Quiet Hours
    val QUIET_HOURS_START: String
        get() = configValues["QUIET_HOURS_START"] ?: "21:00"
    val QUIET_HOURS_END: String
        get() = configValues["QUIET_HOURS_END"] ?: "07:00"
    val MAX_REMINDERS_PER_DAY: Int
        get() = configValues["MAX_REMINDERS_PER_DAY"]?.toIntOrNull() ?: 3

    // Canary Rollout
    val CANARY_PERCENTAGE: Int
        get() = configValues["CANARY_PERCENTAGE"]?.toIntOrNull() ?: 10

    // Image / Memory
    val IMAGE_LRU_CACHE_MB: Int
        get() = configValues["IMAGE_LRU_CACHE_MB"]?.toIntOrNull() ?: 20
    const val IMAGE_MAX_DIMENSION_PX = 1920
    const val IMAGE_QUALITY = 85

    // Compensation
    val COMPENSATION_AUTO_APPROVE_MAX: Double
        get() = configValues["COMPENSATION_AUTO_APPROVE_MAX"]?.toDoubleOrNull() ?: 10.0
    const val COMPENSATION_MIN = 3.0
    const val COMPENSATION_MAX = 20.0

    // Meal Swap Tolerances
    const val SWAP_CALORIE_TOLERANCE_PERCENT = 10.0
    const val SWAP_PROTEIN_TOLERANCE_GRAMS = 5.0

    // Learning Plan
    const val MAX_FREQUENCY_PER_WEEK = 7

    // Rules Engine
    const val HYSTERESIS_DEFAULT_ENTER = 80.0
    const val HYSTERESIS_DEFAULT_EXIT = 90.0
    const val MIN_DURATION_DEFAULT_MINUTES = 10

    // Coupon Limits
    const val DEFAULT_MAX_COUPONS_PER_USER = 2
    const val DEFAULT_COUPON_PERIOD_DAYS = 30

    // Logging
    val LOG_LEVEL: String
        get() = configValues["LOG_LEVEL"] ?: "DEBUG"
    val LOG_REDACTION_ENABLED: Boolean
        get() = configValues["LOG_REDACTION_ENABLED"]?.toBooleanStrictOrNull() ?: true

    // Audit
    val AUDIT_APPEND_ONLY: Boolean
        get() = configValues["AUDIT_APPEND_ONLY"]?.toBooleanStrictOrNull() ?: true

    // Business Calendar (weekdays only for SLA)
    val SLA_BUSINESS_DAYS: List<Int>
        get() = listOf(1, 2, 3, 4, 5) // Monday to Friday

    val SLA_BUSINESS_HOUR_START: Int get() = 9  // 9 AM
    val SLA_BUSINESS_HOUR_END: Int get() = 18   // 6 PM

    private val configValues = mutableMapOf<String, String>()

    fun initialize(context: Context) {
        // 1. Load from environment variables (Docker/CI environments)
        loadFromEnvironment()

        // 2. Load from AndroidManifest <meta-data> (production builds)
        loadFromMetadata(context)

        // 3. Derive DB encryption key from Android Keystore if not provided via env/metadata
        if (!configValues.containsKey("DB_ENCRYPTION_KEY")) {
            try {
                configValues["DB_ENCRYPTION_KEY"] = DatabaseKeyManager.getOrCreateDatabaseKey(context)
            } catch (e: Exception) {
                AppLogger.error("Config", "Failed to derive database key from Android Keystore", e)
                throw IllegalStateException(
                    "Cannot initialize database encryption: Android Keystore unavailable", e
                )
            }
        }

        AppLogger.info("Config", "AppConfig initialized with ${configValues.size} overrides from env/metadata")
    }

    private fun loadFromEnvironment() {
        val envKeys = listOf(
            "DB_ENCRYPTION_KEY", "ENABLE_TLS", "MAX_LOGIN_ATTEMPTS",
            "LOCKOUT_DURATION_MINUTES", "SLA_FIRST_RESPONSE_HOURS",
            "SLA_RESOLUTION_DAYS", "QUIET_HOURS_START", "QUIET_HOURS_END",
            "MAX_REMINDERS_PER_DAY", "CANARY_PERCENTAGE", "IMAGE_LRU_CACHE_MB",
            "COMPENSATION_AUTO_APPROVE_MAX", "LOG_LEVEL", "LOG_REDACTION_ENABLED",
            "AUDIT_APPEND_ONLY"
        )
        for (key in envKeys) {
            val value = System.getenv(key)
            if (value != null) {
                configValues[key] = value
            }
        }
    }

    private fun loadFromMetadata(context: Context) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, android.content.pm.PackageManager.GET_META_DATA
            )
            val metaData = appInfo.metaData ?: return
            for (key in metaData.keySet()) {
                val value = metaData.getString(key)
                if (value != null && !configValues.containsKey(key)) {
                    configValues[key] = value
                }
            }
        } catch (e: Exception) {
            AppLogger.warn("Config", "Could not load metadata: ${e.message}")
        }
    }

    fun setForTesting(key: String, value: String) {
        configValues[key] = value
    }

    fun clearTestOverrides() {
        configValues.clear()
    }
}

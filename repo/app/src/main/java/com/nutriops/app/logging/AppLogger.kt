package com.nutriops.app.logging

import android.util.Log
import com.nutriops.app.config.AppConfig

/**
 * Centralized logging with structured format and automatic PII/sensitive data redaction.
 * Format: [module][sub-module] message
 *
 * All application logging MUST go through this logger to ensure:
 * 1. Consistent structured format
 * 2. Automatic redaction of sensitive data
 * 3. Configurable log levels
 * 4. No sensitive data leakage in logs
 */
object AppLogger {

    private const val TAG = "NutriOps"

    private val SENSITIVE_PATTERNS = listOf(
        Regex("passwordHash[\":\\s]*[\"']?[^\"',\\s}]+", RegexOption.IGNORE_CASE),
        Regex("password[\":\\s]*[\"']?[^\"',\\s}]+", RegexOption.IGNORE_CASE),
        Regex("token[\":\\s]*[\"']?[^\"',\\s}]+", RegexOption.IGNORE_CASE),
        Regex("secret[\":\\s]*[\"']?[^\"',\\s}]+", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"), // SSN
        Regex("encryptionKey[\":\\s]*[\"']?[^\"',\\s}]+", RegexOption.IGNORE_CASE),
        Regex("compensationAmount[\":\\s]*[\"']?[\\d.]+", RegexOption.IGNORE_CASE),
        Regex("creditCard[\":\\s]*[\"']?[\\d-]+", RegexOption.IGNORE_CASE),
    )

    private val REDACTION_REPLACEMENT = "***REDACTED***"

    fun debug(module: String, message: String, subModule: String? = null) {
        if (shouldLog(LogLevel.DEBUG)) {
            Log.d(TAG, formatMessage(module, subModule, redact(message)))
        }
    }

    fun info(module: String, message: String, subModule: String? = null) {
        if (shouldLog(LogLevel.INFO)) {
            Log.i(TAG, formatMessage(module, subModule, redact(message)))
        }
    }

    fun warn(module: String, message: String, subModule: String? = null) {
        if (shouldLog(LogLevel.WARN)) {
            Log.w(TAG, formatMessage(module, subModule, redact(message)))
        }
    }

    fun error(module: String, message: String, throwable: Throwable? = null, subModule: String? = null) {
        if (shouldLog(LogLevel.ERROR)) {
            val formatted = formatMessage(module, subModule, redact(message))
            if (throwable != null) {
                Log.e(TAG, formatted, throwable)
            } else {
                Log.e(TAG, formatted)
            }
        }
    }

    fun audit(module: String, action: String, actorId: String, entityType: String, entityId: String) {
        Log.i(TAG, formatMessage(module, "audit",
            "action=$action actor=$actorId entity=$entityType:$entityId"))
    }

    fun request(method: String, path: String, statusCode: Int, durationMs: Long) {
        info("HTTP", "method=$method path=$path status=$statusCode duration=${durationMs}ms")
    }

    private fun formatMessage(module: String, subModule: String?, message: String): String {
        return if (subModule != null) {
            "[$module][$subModule] $message"
        } else {
            "[$module] $message"
        }
    }

    fun redact(message: String): String {
        if (!AppConfig.LOG_REDACTION_ENABLED) return message
        var result = message
        for (pattern in SENSITIVE_PATTERNS) {
            result = pattern.replace(result, REDACTION_REPLACEMENT)
        }
        return result
    }

    private fun shouldLog(level: LogLevel): Boolean {
        val configLevel = try {
            LogLevel.valueOf(AppConfig.LOG_LEVEL.uppercase())
        } catch (_: Exception) {
            LogLevel.DEBUG
        }
        return level.ordinal >= configLevel.ordinal
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}

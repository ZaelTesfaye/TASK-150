package com.nutriops.app.unit_tests.logging

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.config.AppConfig
import com.nutriops.app.logging.AppLogger
import org.junit.After
import org.junit.Before
import org.junit.Test

class AppLoggerTest {

    @Before
    fun setup() {
        AppConfig.setForTesting("LOG_REDACTION_ENABLED", "true")
    }

    @After
    fun teardown() {
        AppConfig.clearTestOverrides()
    }

    @Test
    fun `redact removes password from log messages`() {
        val message = """User login: {"username":"admin","password":"secret123"}"""
        val redacted = AppLogger.redact(message)
        assertThat(redacted).doesNotContain("secret123")
        assertThat(redacted).contains("***REDACTED***")
    }

    @Test
    fun `redact removes token from log messages`() {
        val message = "API call with token: abc123def456"
        val redacted = AppLogger.redact(message)
        assertThat(redacted).doesNotContain("abc123def456")
    }

    @Test
    fun `redact removes SSN pattern`() {
        val message = "User SSN is 123-45-6789"
        val redacted = AppLogger.redact(message)
        assertThat(redacted).doesNotContain("123-45-6789")
        assertThat(redacted).contains("***REDACTED***")
    }

    @Test
    fun `redact removes compensationAmount`() {
        val message = """{"compensationAmount": 15.50}"""
        val redacted = AppLogger.redact(message)
        assertThat(redacted).doesNotContain("15.50")
    }

    @Test
    fun `redact removes passwordHash`() {
        val message = """{"passwordHash":"abc123def456"}"""
        val redacted = AppLogger.redact(message)
        assertThat(redacted).doesNotContain("abc123def456")
    }

    @Test
    fun `redact preserves non-sensitive content`() {
        val message = "User logged in successfully from main screen"
        val redacted = AppLogger.redact(message)
        assertThat(redacted).isEqualTo(message)
    }

    @Test
    fun `redact is disabled when LOG_REDACTION_ENABLED is false`() {
        AppConfig.setForTesting("LOG_REDACTION_ENABLED", "false")
        val message = """{"password":"secret123"}"""
        val redacted = AppLogger.redact(message)
        assertThat(redacted).isEqualTo(message)
    }
}

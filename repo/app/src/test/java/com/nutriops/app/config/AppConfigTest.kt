package com.nutriops.app.config

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class AppConfigTest {

    @After
    fun teardown() {
        AppConfig.clearTestOverrides()
    }

    @Test
    fun `default SLA first response hours is 4`() {
        assertThat(AppConfig.SLA_FIRST_RESPONSE_HOURS).isEqualTo(4)
    }

    @Test
    fun `default SLA resolution days is 3`() {
        assertThat(AppConfig.SLA_RESOLUTION_DAYS).isEqualTo(3)
    }

    @Test
    fun `default max login attempts is 5`() {
        assertThat(AppConfig.MAX_LOGIN_ATTEMPTS).isEqualTo(5)
    }

    @Test
    fun `default lockout duration is 30 minutes`() {
        assertThat(AppConfig.LOCKOUT_DURATION_MINUTES).isEqualTo(30L)
    }

    @Test
    fun `default quiet hours are 21-00 to 07-00`() {
        assertThat(AppConfig.QUIET_HOURS_START).isEqualTo("21:00")
        assertThat(AppConfig.QUIET_HOURS_END).isEqualTo("07:00")
    }

    @Test
    fun `default max reminders per day is 3`() {
        assertThat(AppConfig.MAX_REMINDERS_PER_DAY).isEqualTo(3)
    }

    @Test
    fun `default canary percentage is 10`() {
        assertThat(AppConfig.CANARY_PERCENTAGE).isEqualTo(10)
    }

    @Test
    fun `default image LRU cache is 20 MB`() {
        assertThat(AppConfig.IMAGE_LRU_CACHE_MB).isEqualTo(20)
    }

    @Test
    fun `default compensation auto approve max is 10`() {
        assertThat(AppConfig.COMPENSATION_AUTO_APPROVE_MAX).isEqualTo(10.0)
    }

    @Test
    fun `swap tolerances are correct`() {
        assertThat(AppConfig.SWAP_CALORIE_TOLERANCE_PERCENT).isEqualTo(10.0)
        assertThat(AppConfig.SWAP_PROTEIN_TOLERANCE_GRAMS).isEqualTo(5.0)
    }

    @Test
    fun `test overrides work`() {
        AppConfig.setForTesting("MAX_LOGIN_ATTEMPTS", "10")
        assertThat(AppConfig.MAX_LOGIN_ATTEMPTS).isEqualTo(10)
    }

    @Test
    fun `clearTestOverrides restores defaults`() {
        AppConfig.setForTesting("MAX_LOGIN_ATTEMPTS", "10")
        AppConfig.clearTestOverrides()
        assertThat(AppConfig.MAX_LOGIN_ATTEMPTS).isEqualTo(5)
    }

    @Test
    fun `business days are Monday to Friday`() {
        assertThat(AppConfig.SLA_BUSINESS_DAYS).containsExactly(1, 2, 3, 4, 5)
    }

    @Test
    fun `TLS is disabled by default`() {
        assertThat(AppConfig.ENABLE_TLS).isFalse()
    }

    @Test
    fun `audit append only is enabled by default`() {
        assertThat(AppConfig.AUDIT_APPEND_ONLY).isTrue()
    }
}

package com.nutriops.app.unit_tests.security

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.RbacManager
import org.junit.Test

class RbacManagerTest {

    // ── Administrator permissions ──

    @Test
    fun `admin can manage config`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.MANAGE_CONFIG)).isTrue()
    }

    @Test
    fun `admin can manage rules`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.MANAGE_RULES)).isTrue()
    }

    @Test
    fun `admin can manage rollouts`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.MANAGE_ROLLOUTS)).isTrue()
    }

    @Test
    fun `admin can manage users`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.MANAGE_USERS)).isTrue()
    }

    @Test
    fun `admin can view audit trail`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.VIEW_AUDIT_TRAIL)).isTrue()
    }

    @Test
    fun `admin cannot create ticket directly`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.CREATE_TICKET)).isFalse()
    }

    @Test
    fun `admin cannot reveal PII`() {
        assertThat(RbacManager.hasPermission(Role.ADMINISTRATOR, RbacManager.Permission.REVEAL_PII)).isFalse()
    }

    // ── Agent permissions ──

    @Test
    fun `agent can manage tickets`() {
        assertThat(RbacManager.hasPermission(Role.AGENT, RbacManager.Permission.MANAGE_TICKETS)).isTrue()
    }

    @Test
    fun `agent can reveal PII`() {
        assertThat(RbacManager.hasPermission(Role.AGENT, RbacManager.Permission.REVEAL_PII)).isTrue()
    }

    @Test
    fun `agent can approve compensation`() {
        assertThat(RbacManager.hasPermission(Role.AGENT, RbacManager.Permission.APPROVE_COMPENSATION)).isTrue()
    }

    @Test
    fun `agent cannot manage config`() {
        assertThat(RbacManager.hasPermission(Role.AGENT, RbacManager.Permission.MANAGE_CONFIG)).isFalse()
    }

    @Test
    fun `agent cannot manage rules`() {
        assertThat(RbacManager.hasPermission(Role.AGENT, RbacManager.Permission.MANAGE_RULES)).isFalse()
    }

    // ── End User permissions ──

    @Test
    fun `end user can create meal plan`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.CREATE_MEAL_PLAN)).isTrue()
    }

    @Test
    fun `end user can swap meal`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.SWAP_MEAL)).isTrue()
    }

    @Test
    fun `end user can create ticket`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.CREATE_TICKET)).isTrue()
    }

    @Test
    fun `end user can transition learning plan`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.TRANSITION_LEARNING_PLAN)).isTrue()
    }

    @Test
    fun `end user cannot manage config`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.MANAGE_CONFIG)).isFalse()
    }

    @Test
    fun `end user cannot manage tickets`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.MANAGE_TICKETS)).isFalse()
    }

    @Test
    fun `end user cannot view audit trail`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.VIEW_AUDIT_TRAIL)).isFalse()
    }

    @Test
    fun `end user cannot reveal PII`() {
        assertThat(RbacManager.hasPermission(Role.END_USER, RbacManager.Permission.REVEAL_PII)).isFalse()
    }

    // ── Object-level authorization (BOLA/IDOR) ──

    @Test
    fun `user can access own data`() {
        val result = RbacManager.checkObjectOwnership("user1", "user1", Role.END_USER)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `user cannot access other users data`() {
        val result = RbacManager.checkObjectOwnership("user1", "user2", Role.END_USER)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `admin can access any users data`() {
        val result = RbacManager.checkObjectOwnership("admin1", "user2", Role.ADMINISTRATOR)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `agent can access any users data for tickets`() {
        val result = RbacManager.checkObjectOwnership("agent1", "user2", Role.AGENT)
        assertThat(result.isSuccess).isTrue()
    }

    // ── checkPermission Result ──

    @Test
    fun `checkPermission returns failure with SecurityException`() {
        val result = RbacManager.checkPermission(Role.END_USER, RbacManager.Permission.MANAGE_CONFIG)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }
}

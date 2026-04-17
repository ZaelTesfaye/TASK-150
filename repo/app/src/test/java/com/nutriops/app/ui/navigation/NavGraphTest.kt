package com.nutriops.app.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.Role
import org.junit.Test

class NavGraphTest {

    // ── Start destination resolution ──

    @Test
    fun `unauthenticated state starts at login`() {
        val dest = resolveStartDestination(
            needsBootstrap = false, isAuthenticated = false, role = ""
        )
        assertThat(dest).isEqualTo(Routes.LOGIN)
    }

    @Test
    fun `first-run state starts at bootstrap even if authenticated flag is false`() {
        val dest = resolveStartDestination(
            needsBootstrap = true, isAuthenticated = false, role = ""
        )
        assertThat(dest).isEqualTo(Routes.BOOTSTRAP)
    }

    @Test
    fun `authenticated administrator starts at admin dashboard`() {
        val dest = resolveStartDestination(
            needsBootstrap = false, isAuthenticated = true, role = "ADMINISTRATOR"
        )
        assertThat(dest).isEqualTo(Routes.ADMIN_DASHBOARD)
    }

    @Test
    fun `authenticated agent starts at agent dashboard`() {
        val dest = resolveStartDestination(
            needsBootstrap = false, isAuthenticated = true, role = "AGENT"
        )
        assertThat(dest).isEqualTo(Routes.AGENT_DASHBOARD)
    }

    @Test
    fun `authenticated end user starts at user dashboard`() {
        val dest = resolveStartDestination(
            needsBootstrap = false, isAuthenticated = true, role = "END_USER"
        )
        assertThat(dest).isEqualTo(Routes.USER_DASHBOARD)
    }

    @Test
    fun `unknown role falls back to user dashboard`() {
        val dest = resolveStartDestination(
            needsBootstrap = false, isAuthenticated = true, role = "???"
        )
        assertThat(dest).isEqualTo(Routes.USER_DASHBOARD)
    }

    // ── Route allow-list ──

    @Test
    fun `null role can access login, bootstrap, register only`() {
        assertThat(isRouteAllowed(Routes.LOGIN, null)).isTrue()
        assertThat(isRouteAllowed(Routes.BOOTSTRAP, null)).isTrue()
        assertThat(isRouteAllowed(Routes.REGISTER, null)).isTrue()
        assertThat(isRouteAllowed(Routes.ADMIN_DASHBOARD, null)).isFalse()
        assertThat(isRouteAllowed(Routes.AGENT_DASHBOARD, null)).isFalse()
        assertThat(isRouteAllowed(Routes.USER_DASHBOARD, null)).isFalse()
    }

    @Test
    fun `admin role can access admin routes but not agent or user routes`() {
        assertThat(isRouteAllowed(Routes.ADMIN_DASHBOARD, Role.ADMINISTRATOR)).isTrue()
        assertThat(isRouteAllowed(Routes.ADMIN_USERS, Role.ADMINISTRATOR)).isTrue()
        assertThat(isRouteAllowed(Routes.AGENT_DASHBOARD, Role.ADMINISTRATOR)).isFalse()
        assertThat(isRouteAllowed(Routes.USER_DASHBOARD, Role.ADMINISTRATOR)).isFalse()
    }

    @Test
    fun `agent role can access agent routes but not admin or user routes`() {
        assertThat(isRouteAllowed(Routes.AGENT_DASHBOARD, Role.AGENT)).isTrue()
        assertThat(isRouteAllowed(Routes.AGENT_TICKETS, Role.AGENT)).isTrue()
        assertThat(isRouteAllowed(Routes.ADMIN_DASHBOARD, Role.AGENT)).isFalse()
        assertThat(isRouteAllowed(Routes.USER_DASHBOARD, Role.AGENT)).isFalse()
    }

    @Test
    fun `end user role can access user routes but not admin or agent routes`() {
        assertThat(isRouteAllowed(Routes.USER_DASHBOARD, Role.END_USER)).isTrue()
        assertThat(isRouteAllowed(Routes.USER_MEAL_PLAN, Role.END_USER)).isTrue()
        assertThat(isRouteAllowed(Routes.ADMIN_DASHBOARD, Role.END_USER)).isFalse()
        assertThat(isRouteAllowed(Routes.AGENT_DASHBOARD, Role.END_USER)).isFalse()
    }

    @Test
    fun `routeRoleMap covers every concrete route defined in Routes`() {
        // Login/Register/Bootstrap are public routes and are not in routeRoleMap,
        // so exclude them.
        val publicRoutes = setOf(Routes.LOGIN, Routes.REGISTER, Routes.BOOTSTRAP)
        val declared = listOf(
            Routes.LOGIN, Routes.REGISTER, Routes.BOOTSTRAP,
            Routes.ADMIN_DASHBOARD, Routes.ADMIN_CONFIG, Routes.ADMIN_RULES,
            Routes.ADMIN_ROLLOUTS, Routes.ADMIN_USERS, Routes.ADMIN_AUDIT,
            Routes.AGENT_DASHBOARD, Routes.AGENT_TICKETS, Routes.AGENT_TICKET_DETAIL,
            Routes.USER_DASHBOARD, Routes.USER_PROFILE, Routes.USER_MEAL_PLAN,
            Routes.USER_LEARNING_PLANS, Routes.USER_MESSAGES, Routes.USER_TICKETS
        ).filterNot { it in publicRoutes }

        for (route in declared) {
            assertThat(routeRoleMap).containsKey(route)
        }
    }
}

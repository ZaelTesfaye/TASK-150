package com.nutriops.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nutriops.app.domain.model.Role
import com.nutriops.app.ui.admin.*
import com.nutriops.app.ui.agent.*
import com.nutriops.app.ui.auth.*
import com.nutriops.app.ui.enduser.*

private val routeRoleMap: Map<String, Set<Role>> = mapOf(
    Routes.ADMIN_DASHBOARD to setOf(Role.ADMINISTRATOR),
    Routes.ADMIN_CONFIG to setOf(Role.ADMINISTRATOR),
    Routes.ADMIN_RULES to setOf(Role.ADMINISTRATOR),
    Routes.ADMIN_ROLLOUTS to setOf(Role.ADMINISTRATOR),
    Routes.ADMIN_USERS to setOf(Role.ADMINISTRATOR),
    Routes.ADMIN_AUDIT to setOf(Role.ADMINISTRATOR),
    Routes.AGENT_DASHBOARD to setOf(Role.AGENT),
    Routes.AGENT_TICKETS to setOf(Role.AGENT),
    Routes.AGENT_TICKET_DETAIL to setOf(Role.AGENT),
    Routes.USER_DASHBOARD to setOf(Role.END_USER),
    Routes.USER_PROFILE to setOf(Role.END_USER),
    Routes.USER_MEAL_PLAN to setOf(Role.END_USER),
    Routes.USER_LEARNING_PLANS to setOf(Role.END_USER),
    Routes.USER_MESSAGES to setOf(Role.END_USER),
    Routes.USER_TICKETS to setOf(Role.END_USER),
)

private fun isRouteAllowed(route: String, role: Role?): Boolean {
    if (role == null) return route == Routes.LOGIN || route == Routes.BOOTSTRAP || route == Routes.REGISTER
    val allowedRoles = routeRoleMap[route] ?: return true
    return role in allowedRoles
}

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val BOOTSTRAP = "bootstrap"
    const val ADMIN_DASHBOARD = "admin/dashboard"
    const val ADMIN_CONFIG = "admin/config"
    const val ADMIN_RULES = "admin/rules"
    const val ADMIN_ROLLOUTS = "admin/rollouts"
    const val ADMIN_USERS = "admin/users"
    const val ADMIN_AUDIT = "admin/audit"
    const val AGENT_DASHBOARD = "agent/dashboard"
    const val AGENT_TICKETS = "agent/tickets"
    const val AGENT_TICKET_DETAIL = "agent/ticket/{ticketId}"
    const val USER_DASHBOARD = "user/dashboard"
    const val USER_PROFILE = "user/profile"
    const val USER_MEAL_PLAN = "user/mealplan"
    const val USER_LEARNING_PLANS = "user/learningplans"
    const val USER_MESSAGES = "user/messages"
    const val USER_TICKETS = "user/tickets"
}

@Composable
fun NutriOpsNavHost(
    navController: NavHostController = rememberNavController()
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.uiState.collectAsState()

    val startDestination = when {
        authState.needsBootstrap -> Routes.BOOTSTRAP
        authState.isAuthenticated -> when (authState.role) {
            "ADMINISTRATOR" -> Routes.ADMIN_DASHBOARD
            "AGENT" -> Routes.AGENT_DASHBOARD
            else -> Routes.USER_DASHBOARD
        }
        else -> Routes.LOGIN
    }

    NavHost(navController = navController, startDestination = startDestination) {
        // ── Auth ──
        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = { role ->
                    val dest = when (role) {
                        "ADMINISTRATOR" -> Routes.ADMIN_DASHBOARD
                        "AGENT" -> Routes.AGENT_DASHBOARD
                        else -> Routes.USER_DASHBOARD
                    }
                    navController.navigate(dest) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onNeedsBootstrap = {
                    navController.navigate(Routes.BOOTSTRAP) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                viewModel = authViewModel,
                onRegisterSuccess = {
                    navController.navigate(Routes.USER_DASHBOARD) { popUpTo(0) { inclusive = true } }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.BOOTSTRAP) {
            BootstrapScreen(
                viewModel = authViewModel,
                onBootstrapSuccess = {
                    navController.navigate(Routes.ADMIN_DASHBOARD) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        // ── Administrator ──
        composable(Routes.ADMIN_DASHBOARD) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_DASHBOARD, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminDashboardScreen(
                onNavigateToConfig = { navController.navigate(Routes.ADMIN_CONFIG) },
                onNavigateToRules = { navController.navigate(Routes.ADMIN_RULES) },
                onNavigateToRollouts = { navController.navigate(Routes.ADMIN_ROLLOUTS) },
                onNavigateToUsers = { navController.navigate(Routes.ADMIN_USERS) },
                onNavigateToAudit = { navController.navigate(Routes.ADMIN_AUDIT) },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        composable(Routes.ADMIN_CONFIG) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_CONFIG, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminConfigScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_RULES) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_RULES, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminRulesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_ROLLOUTS) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_ROLLOUTS, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminRolloutsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_USERS) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_USERS, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminUsersScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_AUDIT) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.ADMIN_AUDIT, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AdminAuditScreen(onBack = { navController.popBackStack() })
        }

        // ── Agent ──
        composable(Routes.AGENT_DASHBOARD) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.AGENT_DASHBOARD, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AgentDashboardScreen(
                onNavigateToTickets = { navController.navigate(Routes.AGENT_TICKETS) },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        composable(Routes.AGENT_TICKETS) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.AGENT_TICKETS, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            AgentTicketListScreen(
                onTicketClick = { ticketId ->
                    navController.navigate("agent/ticket/$ticketId")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.AGENT_TICKET_DETAIL,
            arguments = listOf(navArgument("ticketId") { type = NavType.StringType })
        ) { backStackEntry ->
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.AGENT_TICKET_DETAIL, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            val ticketId = backStackEntry.arguments?.getString("ticketId") ?: ""
            AgentTicketDetailScreen(
                ticketId = ticketId,
                onBack = { navController.popBackStack() }
            )
        }

        // ── End User ──
        composable(Routes.USER_DASHBOARD) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_DASHBOARD, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserDashboardScreen(
                onNavigateToProfile = { navController.navigate(Routes.USER_PROFILE) },
                onNavigateToMealPlan = { navController.navigate(Routes.USER_MEAL_PLAN) },
                onNavigateToLearningPlans = { navController.navigate(Routes.USER_LEARNING_PLANS) },
                onNavigateToMessages = { navController.navigate(Routes.USER_MESSAGES) },
                onNavigateToTickets = { navController.navigate(Routes.USER_TICKETS) },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        composable(Routes.USER_PROFILE) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_PROFILE, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserProfileScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.USER_MEAL_PLAN) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_MEAL_PLAN, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserMealPlanScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.USER_LEARNING_PLANS) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_LEARNING_PLANS, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserLearningPlanScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.USER_MESSAGES) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_MESSAGES, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserMessagesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.USER_TICKETS) {
            val currentRole = try { Role.valueOf(authState.role) } catch (_: Exception) { null }
            if (!isRouteAllowed(Routes.USER_TICKETS, currentRole)) {
                LaunchedEffect(Unit) { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            UserTicketsScreen(onBack = { navController.popBackStack() })
        }
    }
}

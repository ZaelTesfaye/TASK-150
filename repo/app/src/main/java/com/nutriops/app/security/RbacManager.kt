package com.nutriops.app.security

import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger

/**
 * Role-Based Access Control enforcement.
 *
 * Permission matrix:
 * - ADMINISTRATOR: full config, rules, rollouts, user management, audit read
 * - AGENT: ticket management, evidence review, PII reveal, compensation workflow, audit read
 * - END_USER: own profile, own meal plans, own learning plans, own messages/todos, own tickets (create)
 *
 * Object-level authorization: users can only access their own data (BOLA/IDOR protection).
 */
object RbacManager {

    enum class Permission {
        // User management
        MANAGE_USERS,
        VIEW_ALL_USERS,

        // Profile
        VIEW_OWN_PROFILE,
        EDIT_OWN_PROFILE,
        VIEW_ANY_PROFILE,

        // Meal plans
        VIEW_OWN_MEAL_PLANS,
        CREATE_MEAL_PLAN,
        SWAP_MEAL,
        VIEW_ANY_MEAL_PLANS,

        // Learning plans
        VIEW_OWN_LEARNING_PLANS,
        CREATE_LEARNING_PLAN,
        TRANSITION_LEARNING_PLAN,
        DUPLICATE_LEARNING_PLAN,
        VIEW_ANY_LEARNING_PLANS,

        // Config & Operations
        MANAGE_CONFIG,
        MANAGE_HOMEPAGE_MODULES,
        MANAGE_AD_SLOTS,
        MANAGE_CAMPAIGNS,
        MANAGE_COUPONS,
        MANAGE_BLACKWHITELIST,
        MANAGE_PURCHASE_LIMITS,
        MANAGE_ROLLOUTS,

        // Rules
        MANAGE_RULES,
        VIEW_RULES,
        EVALUATE_RULES,
        BACK_CALCULATE,

        // Tickets
        CREATE_TICKET,
        VIEW_OWN_TICKETS,
        VIEW_ALL_TICKETS,
        MANAGE_TICKETS,
        ASSIGN_TICKET,
        RESOLVE_TICKET,

        // Evidence
        UPLOAD_EVIDENCE,
        VIEW_EVIDENCE,

        // Compensation
        SUGGEST_COMPENSATION,
        APPROVE_COMPENSATION,

        // PII
        REVEAL_PII,

        // Messaging
        VIEW_OWN_MESSAGES,
        MANAGE_TEMPLATES,
        SEND_MESSAGES,

        // Audit
        VIEW_AUDIT_TRAIL,

        // Metrics
        VIEW_METRICS,
        VIEW_OWN_METRICS,
    }

    private val rolePermissions: Map<Role, Set<Permission>> = mapOf(
        Role.ADMINISTRATOR to setOf(
            Permission.MANAGE_USERS,
            Permission.VIEW_ALL_USERS,
            Permission.VIEW_ANY_PROFILE,
            Permission.VIEW_ANY_MEAL_PLANS,
            Permission.SWAP_MEAL,
            Permission.VIEW_ANY_LEARNING_PLANS,
            Permission.MANAGE_CONFIG,
            Permission.MANAGE_HOMEPAGE_MODULES,
            Permission.MANAGE_AD_SLOTS,
            Permission.MANAGE_CAMPAIGNS,
            Permission.MANAGE_COUPONS,
            Permission.MANAGE_BLACKWHITELIST,
            Permission.MANAGE_PURCHASE_LIMITS,
            Permission.MANAGE_ROLLOUTS,
            Permission.MANAGE_RULES,
            Permission.VIEW_RULES,
            Permission.EVALUATE_RULES,
            Permission.BACK_CALCULATE,
            Permission.VIEW_ALL_TICKETS,
            Permission.VIEW_EVIDENCE,
            Permission.MANAGE_TEMPLATES,
            Permission.SEND_MESSAGES,
            Permission.VIEW_AUDIT_TRAIL,
            Permission.VIEW_METRICS,
        ),
        Role.AGENT to setOf(
            Permission.VIEW_OWN_PROFILE,
            Permission.VIEW_ANY_PROFILE,
            Permission.VIEW_ALL_TICKETS,
            Permission.MANAGE_TICKETS,
            Permission.ASSIGN_TICKET,
            Permission.RESOLVE_TICKET,
            Permission.VIEW_EVIDENCE,
            Permission.SUGGEST_COMPENSATION,
            Permission.APPROVE_COMPENSATION,
            Permission.REVEAL_PII,
            Permission.VIEW_AUDIT_TRAIL,
            Permission.VIEW_RULES,
            Permission.VIEW_METRICS,
            Permission.VIEW_OWN_MESSAGES,
        ),
        Role.END_USER to setOf(
            Permission.VIEW_OWN_PROFILE,
            Permission.EDIT_OWN_PROFILE,
            Permission.VIEW_OWN_MEAL_PLANS,
            Permission.CREATE_MEAL_PLAN,
            Permission.SWAP_MEAL,
            Permission.VIEW_OWN_LEARNING_PLANS,
            Permission.CREATE_LEARNING_PLAN,
            Permission.TRANSITION_LEARNING_PLAN,
            Permission.DUPLICATE_LEARNING_PLAN,
            Permission.CREATE_TICKET,
            Permission.VIEW_OWN_TICKETS,
            Permission.UPLOAD_EVIDENCE,
            Permission.VIEW_OWN_MESSAGES,
            Permission.VIEW_OWN_METRICS,
        )
    )

    fun hasPermission(role: Role, permission: Permission): Boolean {
        return rolePermissions[role]?.contains(permission) == true
    }

    fun checkPermission(role: Role, permission: Permission): Result<Unit> {
        return if (hasPermission(role, permission)) {
            Result.success(Unit)
        } else {
            AppLogger.warn("RBAC", "Permission denied: role=$role permission=$permission")
            Result.failure(SecurityException("Permission denied: ${permission.name} not granted to role ${role.name}"))
        }
    }

    fun checkObjectOwnership(actorId: String, ownerId: String, role: Role): Result<Unit> {
        // Administrators and Agents with appropriate permissions can access any object
        if (role == Role.ADMINISTRATOR) return Result.success(Unit)
        if (role == Role.AGENT) return Result.success(Unit) // Agents have cross-user ticket access

        return if (actorId == ownerId) {
            Result.success(Unit)
        } else {
            AppLogger.warn("RBAC", "Object-level authorization failed: actor=$actorId owner=$ownerId")
            Result.failure(SecurityException("Object-level authorization failed: cannot access another user's data"))
        }
    }

    fun getPermissionsForRole(role: Role): Set<Permission> {
        return rolePermissions[role] ?: emptySet()
    }
}

package com.nutriops.app.security

import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Manages authentication state including:
 * - First-run Administrator bootstrap
 * - Login with lockout policy
 * - Session management
 * - Actor identity for all audited writes
 */
class AuthManager(
    private val database: NutriOpsDatabase,
    private val auditManager: com.nutriops.app.audit.AuditManager
) {
    data class AuthSession(
        val userId: String,
        val username: String,
        val role: Role,
        val loginTime: LocalDateTime
    )

    sealed class AuthResult {
        data class Success(val session: AuthSession) : AuthResult()
        data class Failure(val message: String) : AuthResult()
        data object AccountLocked : AuthResult()
        data object NeedsBootstrap : AuthResult()
    }

    private val _currentSession = MutableStateFlow<AuthSession?>(null)
    val currentSession: StateFlow<AuthSession?> = _currentSession.asStateFlow()

    val isAuthenticated: Boolean get() = _currentSession.value != null
    val currentUserId: String get() = _currentSession.value?.userId ?: ""
    val currentRole: Role get() = _currentSession.value?.role ?: Role.END_USER

    fun needsBootstrap(): Boolean {
        val adminCount = database.usersQueries.countUsersByRole("ADMINISTRATOR").executeAsOne()
        return adminCount == 0L
    }

    fun bootstrapAdmin(username: String, password: String): AuthResult {
        if (!needsBootstrap()) {
            return AuthResult.Failure("Administrator already exists")
        }

        if (username.isBlank() || password.length < 8) {
            return AuthResult.Failure("Username required and password must be at least 8 characters")
        }

        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val userId = UUID.randomUUID().toString()
        val hashedPassword = PasswordHasher.hash(password)

        database.usersQueries.insertUser(
            id = userId,
            username = username,
            passwordHash = hashedPassword,
            role = "ADMINISTRATOR",
            isActive = 1,
            isLocked = 0,
            failedLoginAttempts = 0,
            lockoutUntil = null,
            createdAt = now,
            updatedAt = now
        )

        auditManager.log(
            entityType = "User",
            entityId = userId,
            action = AuditAction.CREATE,
            actorId = userId,
            actorRole = Role.ADMINISTRATOR,
            details = """{"event":"admin_bootstrap","username":"$username"}"""
        )

        AppLogger.info("Auth", "Administrator bootstrap completed for user: $username")

        val session = AuthSession(userId, username, Role.ADMINISTRATOR, LocalDateTime.now())
        _currentSession.value = session
        return AuthResult.Success(session)
    }

    fun login(username: String, password: String): AuthResult {
        val user = database.usersQueries.getUserByUsername(username).executeAsOneOrNull()
            ?: return AuthResult.Failure("Invalid credentials")

        if (user.isLocked == 1L) {
            val lockoutUntil = user.lockoutUntil?.let { LocalDateTime.parse(it) }
            if (lockoutUntil != null && LocalDateTime.now().isBefore(lockoutUntil)) {
                AppLogger.warn("Auth", "Login attempt on locked account: $username")
                auditManager.log(
                    entityType = "User",
                    entityId = user.id,
                    action = AuditAction.LOGIN_FAILED,
                    actorId = user.id,
                    actorRole = Role.valueOf(user.role),
                    details = """{"reason":"account_locked"}"""
                )
                return AuthResult.AccountLocked
            }
            // Lockout expired, reset
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            database.usersQueries.resetLoginAttempts(now, user.id)
        }

        if (user.isActive != 1L) {
            return AuthResult.Failure("Account is deactivated")
        }

        if (!PasswordHasher.verify(password, user.passwordHash)) {
            val newAttempts = user.failedLoginAttempts + 1
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            if (newAttempts >= AppConfig.MAX_LOGIN_ATTEMPTS) {
                val lockoutUntil = LocalDateTime.now()
                    .plusMinutes(AppConfig.LOCKOUT_DURATION_MINUTES)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                database.usersQueries.updateLoginFailure(
                    failedLoginAttempts = newAttempts,
                    isLocked = 1,
                    lockoutUntil = lockoutUntil,
                    updatedAt = now,
                    id = user.id
                )

                auditManager.log(
                    entityType = "User",
                    entityId = user.id,
                    action = AuditAction.LOCK_ACCOUNT,
                    actorId = user.id,
                    actorRole = Role.valueOf(user.role),
                    details = """{"failedAttempts":$newAttempts,"lockoutMinutes":${AppConfig.LOCKOUT_DURATION_MINUTES}}"""
                )

                AppLogger.warn("Auth", "Account locked after $newAttempts failed attempts: $username")
                return AuthResult.AccountLocked
            }

            database.usersQueries.updateLoginFailure(
                failedLoginAttempts = newAttempts,
                isLocked = 0,
                lockoutUntil = null,
                updatedAt = now,
                id = user.id
            )

            auditManager.log(
                entityType = "User",
                entityId = user.id,
                action = AuditAction.LOGIN_FAILED,
                actorId = user.id,
                actorRole = Role.valueOf(user.role),
                details = """{"failedAttempts":$newAttempts}"""
            )

            return AuthResult.Failure("Invalid credentials")
        }

        // Successful login: reset attempts
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        database.usersQueries.resetLoginAttempts(now, user.id)

        val role = Role.valueOf(user.role)
        val session = AuthSession(user.id, user.username, role, LocalDateTime.now())
        _currentSession.value = session

        auditManager.log(
            entityType = "User",
            entityId = user.id,
            action = AuditAction.LOGIN,
            actorId = user.id,
            actorRole = role,
            details = """{"event":"login_success"}"""
        )

        AppLogger.info("Auth", "Login successful: $username (role=${role.name})")
        return AuthResult.Success(session)
    }

    fun logout() {
        val session = _currentSession.value ?: return
        auditManager.log(
            entityType = "User",
            entityId = session.userId,
            action = AuditAction.LOGOUT,
            actorId = session.userId,
            actorRole = session.role,
            details = """{"event":"logout"}"""
        )
        AppLogger.info("Auth", "Logout: ${session.username}")
        _currentSession.value = null
    }

    fun requireRole(vararg requiredRoles: Role): Boolean {
        val session = _currentSession.value ?: return false
        return session.role in requiredRoles
    }
}

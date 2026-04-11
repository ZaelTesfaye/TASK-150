package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import com.nutriops.app.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val queries get() = database.usersQueries

    suspend fun createUser(
        username: String,
        password: String,
        role: Role,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val existing = queries.getUserByUsername(username).executeAsOneOrNull()
            if (existing != null) {
                return@withContext Result.failure(IllegalArgumentException("Username already exists"))
            }

            val userId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val hashedPassword = PasswordHasher.hash(password)

            auditManager.logWithTransaction(
                entityType = "User",
                entityId = userId,
                action = AuditAction.CREATE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"username":"$username","role":"${role.name}"}"""
            ) {
                queries.insertUser(
                    id = userId,
                    username = username,
                    passwordHash = hashedPassword,
                    role = role.name,
                    isActive = 1,
                    isLocked = 0,
                    failedLoginAttempts = 0,
                    lockoutUntil = null,
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("UserRepo", "User created: $username (role=${role.name})")
            Result.success(userId)
        } catch (e: Exception) {
            AppLogger.error("UserRepo", "Failed to create user", e)
            Result.failure(e)
        }
    }

    suspend fun getUserById(id: String) = withContext(Dispatchers.IO) {
        queries.getUserById(id).executeAsOneOrNull()
    }

    suspend fun getUserByUsername(username: String) = withContext(Dispatchers.IO) {
        queries.getUserByUsername(username).executeAsOneOrNull()
    }

    suspend fun getAllUsers() = withContext(Dispatchers.IO) {
        queries.getAllUsers().executeAsList()
    }

    suspend fun getUsersByRole(role: Role) = withContext(Dispatchers.IO) {
        queries.getUsersByRole(role.name).executeAsList()
    }

    suspend fun deactivateUser(userId: String, actorId: String, actorRole: Role): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val user = queries.getUserById(userId).executeAsOneOrNull()
                    ?: return@withContext Result.failure(IllegalArgumentException("User not found"))

                val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                auditManager.logWithTransaction(
                    entityType = "User",
                    entityId = userId,
                    action = AuditAction.UPDATE,
                    actorId = actorId,
                    actorRole = actorRole,
                    previousState = """{"isActive":${user.isActive}}""",
                    newState = """{"isActive":0}""",
                    details = """{"action":"deactivate"}"""
                ) {
                    queries.updateUser(
                        username = user.username,
                        passwordHash = user.passwordHash,
                        role = user.role,
                        isActive = 0,
                        isLocked = user.isLocked,
                        failedLoginAttempts = user.failedLoginAttempts,
                        lockoutUntil = user.lockoutUntil,
                        updatedAt = now,
                        id = userId
                    )
                }

                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.error("UserRepo", "Failed to deactivate user", e)
                Result.failure(e)
            }
        }

    suspend fun countUsers() = withContext(Dispatchers.IO) {
        queries.countUsers().executeAsOne()
    }
}

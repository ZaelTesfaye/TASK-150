package com.nutriops.app.data.repository

import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.AuditAction
import com.nutriops.app.domain.model.Role
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val database: NutriOpsDatabase,
    private val auditManager: AuditManager
) {
    private val queries get() = database.profilesQueries

    suspend fun createProfile(
        userId: String,
        ageRange: String,
        dietaryPattern: String,
        allergies: String,
        goal: String,
        preferredMealTimes: String,
        dailyCalorieBudget: Long,
        proteinTargetGrams: Long,
        carbTargetGrams: Long,
        fatTargetGrams: Long,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val existing = queries.getProfileByUserId(userId).executeAsOneOrNull()
            if (existing != null) {
                return@withContext Result.failure(IllegalArgumentException("Profile already exists for user"))
            }

            val profileId = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Profile",
                entityId = profileId,
                action = AuditAction.CREATE,
                actorId = actorId,
                actorRole = actorRole,
                details = """{"userId":"$userId","goal":"$goal"}"""
            ) {
                queries.insertProfile(
                    id = profileId,
                    userId = userId,
                    ageRange = ageRange,
                    dietaryPattern = dietaryPattern,
                    allergies = allergies,
                    goal = goal,
                    preferredMealTimes = preferredMealTimes,
                    dailyCalorieBudget = dailyCalorieBudget,
                    proteinTargetGrams = proteinTargetGrams,
                    carbTargetGrams = carbTargetGrams,
                    fatTargetGrams = fatTargetGrams,
                    createdAt = now,
                    updatedAt = now
                )
            }

            AppLogger.info("ProfileRepo", "Profile created for user: $userId")
            Result.success(profileId)
        } catch (e: Exception) {
            AppLogger.error("ProfileRepo", "Failed to create profile", e)
            Result.failure(e)
        }
    }

    suspend fun getProfileByUserId(userId: String) = withContext(Dispatchers.IO) {
        queries.getProfileByUserId(userId).executeAsOneOrNull()
    }

    suspend fun updateProfile(
        profileId: String,
        ageRange: String,
        dietaryPattern: String,
        allergies: String,
        goal: String,
        preferredMealTimes: String,
        dailyCalorieBudget: Long,
        proteinTargetGrams: Long,
        carbTargetGrams: Long,
        fatTargetGrams: Long,
        actorId: String,
        actorRole: Role
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = queries.getProfileById(profileId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Profile not found"))

            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            auditManager.logWithTransaction(
                entityType = "Profile",
                entityId = profileId,
                action = AuditAction.UPDATE,
                actorId = actorId,
                actorRole = actorRole,
                previousState = """{"goal":"${existing.goal}","calories":${existing.dailyCalorieBudget}}""",
                newState = """{"goal":"$goal","calories":$dailyCalorieBudget}"""
            ) {
                queries.updateProfile(
                    ageRange = ageRange,
                    dietaryPattern = dietaryPattern,
                    allergies = allergies,
                    goal = goal,
                    preferredMealTimes = preferredMealTimes,
                    dailyCalorieBudget = dailyCalorieBudget,
                    proteinTargetGrams = proteinTargetGrams,
                    carbTargetGrams = carbTargetGrams,
                    fatTargetGrams = fatTargetGrams,
                    updatedAt = now,
                    id = profileId
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error("ProfileRepo", "Failed to update profile", e)
            Result.failure(e)
        }
    }
}

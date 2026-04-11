package com.nutriops.app.domain.usecase.auth

import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

class ManageUsersUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend fun createUser(
        username: String,
        password: String,
        role: Role,
        actorId: String,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_USERS)
            .getOrElse { return Result.failure(it) }
        return userRepository.createUser(username, password, role, actorId, actorRole)
    }

    suspend fun getAllUsers(actorRole: Role): Result<List<com.nutriops.app.data.local.Users>> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_ALL_USERS)
            .getOrElse { return Result.failure(it) }
        return Result.success(userRepository.getAllUsers())
    }

    suspend fun deactivateUser(
        userId: String,
        actorId: String,
        actorRole: Role
    ): Result<Unit> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.MANAGE_USERS)
            .getOrElse { return Result.failure(it) }
        return userRepository.deactivateUser(userId, actorId, actorRole)
    }
}

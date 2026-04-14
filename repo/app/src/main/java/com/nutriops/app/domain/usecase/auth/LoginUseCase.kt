package com.nutriops.app.domain.usecase.auth

import com.nutriops.app.security.AuthManager
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authManager: AuthManager
) {
    fun needsBootstrap(): Boolean = authManager.needsBootstrap()

    fun bootstrap(username: String, password: String): AuthManager.AuthResult =
        authManager.bootstrapAdmin(username, password)

    fun login(username: String, password: String): AuthManager.AuthResult =
        authManager.login(username, password)

    fun register(username: String, password: String): AuthManager.AuthResult =
        authManager.register(username, password)

    fun logout() = authManager.logout()

    fun isAuthenticated(): Boolean = authManager.isAuthenticated
}

package com.nutriops.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val needsBootstrap: Boolean = false,
    val role: String = "",
    val username: String = "",
    val error: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkAuthState()
    }

    private fun checkAuthState() {
        val needsBootstrap = loginUseCase.needsBootstrap()
        val session = authManager.currentSession.value
        _uiState.value = AuthUiState(
            isAuthenticated = session != null,
            needsBootstrap = needsBootstrap,
            role = session?.role?.name ?: "",
            username = session?.username ?: ""
        )
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Username and password are required")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            when (val result = loginUseCase.login(username, password)) {
                is AuthManager.AuthResult.Success -> {
                    _uiState.value = AuthUiState(
                        isAuthenticated = true,
                        role = result.session.role.name,
                        username = result.session.username
                    )
                }
                is AuthManager.AuthResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is AuthManager.AuthResult.AccountLocked -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Account is locked. Please try again later.")
                }
                is AuthManager.AuthResult.NeedsBootstrap -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, needsBootstrap = true)
                }
            }
        }
    }

    fun bootstrap(username: String, password: String) {
        if (username.isBlank() || password.length < 8) {
            _uiState.value = _uiState.value.copy(error = "Username required, password must be 8+ characters")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            when (val result = loginUseCase.bootstrap(username, password)) {
                is AuthManager.AuthResult.Success -> {
                    _uiState.value = AuthUiState(
                        isAuthenticated = true,
                        role = result.session.role.name,
                        username = result.session.username
                    )
                }
                is AuthManager.AuthResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Bootstrap failed")
                }
            }
        }
    }

    fun logout() {
        loginUseCase.logout()
        _uiState.value = AuthUiState(needsBootstrap = loginUseCase.needsBootstrap())
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

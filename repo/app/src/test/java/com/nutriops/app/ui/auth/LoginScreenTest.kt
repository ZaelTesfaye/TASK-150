package com.nutriops.app.ui.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(
        needsBootstrap: Boolean = false,
        initialSession: AuthManager.AuthSession? = null
    ): Pair<AuthViewModel, LoginUseCase> {
        val loginUseCase: LoginUseCase = mockk(relaxed = true)
        val authManager: AuthManager = mockk(relaxed = true)
        every { loginUseCase.needsBootstrap() } returns needsBootstrap
        every { authManager.currentSession } returns MutableStateFlow(initialSession)
        val vm = AuthViewModel(loginUseCase, authManager)
        return vm to loginUseCase
    }

    @Test
    fun `renders username field, password field and sign-in button`() {
        val (vm, _) = buildViewModel()

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeTestRule.onNodeWithText("New here? Create an account").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing credentials and tapping Sign In invokes login use case`() {
        val (vm, loginUseCase) = buildViewModel()
        every { loginUseCase.login("alice", "password123") } returns
            AuthManager.AuthResult.Failure("stub — we only need to verify the call")

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.waitForIdle()
        verify { loginUseCase.login("alice", "password123") }
    }

    @Test
    fun `onLoginSuccess callback fires with role name after successful login`() {
        val (vm, loginUseCase) = buildViewModel()
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { loginUseCase.login("alice", "password123") } returns
            AuthManager.AuthResult.Success(session)

        var loggedInRole: String? = null
        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = { role -> loggedInRole = role },
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Username").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 2_000) { loggedInRole != null }
        assert(loggedInRole == Role.END_USER.name)
    }

    @Test
    fun `shows error message when credentials are blank`() {
        val (vm, _) = buildViewModel()

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = {}
            )
        }

        composeTestRule.onNodeWithText("Sign In").performClick()

        composeTestRule.onNodeWithText("Username and password are required").assertIsDisplayed()
    }

    @Test
    fun `tapping register link triggers navigation callback`() {
        val (vm, _) = buildViewModel()
        var navigated = false

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = {},
                onNavigateToRegister = { navigated = true }
            )
        }

        composeTestRule.onNodeWithText("New here? Create an account").performScrollTo().performClick()

        assert(navigated)
    }

    @Test
    fun `onNeedsBootstrap fires when bootstrap is required`() {
        val (vm, _) = buildViewModel(needsBootstrap = true)
        var bootstrapCalled = false

        composeTestRule.setContent {
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {},
                onNeedsBootstrap = { bootstrapCalled = true },
                onNavigateToRegister = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 2_000) { bootstrapCalled }
        assert(bootstrapCalled)
    }
}

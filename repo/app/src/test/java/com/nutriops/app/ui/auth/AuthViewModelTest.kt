package com.nutriops.app.ui.auth

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var loginUseCase: LoginUseCase
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        loginUseCase = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        // Default to "no bootstrap needed, no active session"
        every { loginUseCase.needsBootstrap() } returns false
        every { authManager.currentSession } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = AuthViewModel(loginUseCase, authManager)

    // ── Initial state ──

    @Test
    fun `initial state is unauthenticated with no error`() = runTest {
        val vm = newVm()

        val state = vm.uiState.value
        assertThat(state.isAuthenticated).isFalse()
        assertThat(state.needsBootstrap).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `initial state reflects needsBootstrap when first-run`() = runTest {
        every { loginUseCase.needsBootstrap() } returns true

        val vm = newVm()

        assertThat(vm.uiState.value.needsBootstrap).isTrue()
    }

    // ── login ──

    @Test
    fun `successful login transitions state to authenticated with role and username`() = runTest {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { loginUseCase.login("alice", "password123") } returns AuthManager.AuthResult.Success(session)

        val vm = newVm()
        vm.login("alice", "password123")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.isAuthenticated).isTrue()
        assertThat(state.role).isEqualTo(Role.END_USER.name)
        assertThat(state.username).isEqualTo("alice")
        assertThat(state.error).isNull()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `failed login transitions state to error with repository message`() = runTest {
        every { loginUseCase.login(any(), any()) } returns AuthManager.AuthResult.Failure("Invalid credentials")

        val vm = newVm()
        vm.login("alice", "wrong")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.isAuthenticated).isFalse()
        assertThat(state.error).isEqualTo("Invalid credentials")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `locked-account login surfaces distinct error message`() = runTest {
        every { loginUseCase.login(any(), any()) } returns AuthManager.AuthResult.AccountLocked

        val vm = newVm()
        vm.login("alice", "password123")
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).contains("locked")
        assertThat(vm.uiState.value.isAuthenticated).isFalse()
    }

    @Test
    fun `login with blank credentials surfaces inline error without calling use case`() = runTest {
        val vm = newVm()

        vm.login("", "")
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo("Username and password are required")
        verify(exactly = 0) { loginUseCase.login(any(), any()) }
    }

    @Test
    fun `loading state is emitted between login start and result`() = runTest(testDispatcher) {
        // Use StandardTestDispatcher so we can step through coroutine phases
        val stepDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stepDispatcher)

        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        // A login implementation that yields to simulate I/O
        coEvery { loginUseCase.login("alice", "password123") } coAnswers {
            delay(100)
            AuthManager.AuthResult.Success(session)
        }

        val vm = AuthViewModel(loginUseCase, authManager)
        val emissions = mutableListOf<AuthUiState>()

        val collectorScope = CoroutineScope(stepDispatcher)
        val job = collectorScope.launch { vm.uiState.collect { emissions += it } }

        vm.login("alice", "password123")
        // Let the "isLoading = true" snapshot emit before the result arrives
        stepDispatcher.scheduler.runCurrent()

        assertThat(emissions.any { it.isLoading }).isTrue()

        stepDispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.isLoading).isFalse()

        job.cancel()
    }

    // ── register ──

    @Test
    fun `successful register transitions state to authenticated`() = runTest {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { loginUseCase.register("alice", "password123") } returns AuthManager.AuthResult.Success(session)

        val vm = newVm()
        vm.register("alice", "password123")
        advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.username).isEqualTo("alice")
    }

    @Test
    fun `register rejects short password inline without hitting use case`() = runTest {
        val vm = newVm()

        vm.register("alice", "short")
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).contains("8+ characters")
        verify(exactly = 0) { loginUseCase.register(any(), any()) }
    }

    // ── bootstrap ──

    @Test
    fun `successful bootstrap transitions to authenticated as administrator`() = runTest {
        val session = AuthManager.AuthSession("admin1", "admin", Role.ADMINISTRATOR, LocalDateTime.now())
        every { loginUseCase.bootstrap("admin", "adminpass1") } returns AuthManager.AuthResult.Success(session)

        val vm = newVm()
        vm.bootstrap("admin", "adminpass1")
        advanceUntilIdle()

        assertThat(vm.uiState.value.isAuthenticated).isTrue()
        assertThat(vm.uiState.value.role).isEqualTo(Role.ADMINISTRATOR.name)
    }

    // ── logout ──

    @Test
    fun `logout transitions state back to unauthenticated`() = runTest {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { loginUseCase.login(any(), any()) } returns AuthManager.AuthResult.Success(session)

        val vm = newVm()
        vm.login("alice", "password123")
        advanceUntilIdle()
        assertThat(vm.uiState.value.isAuthenticated).isTrue()

        vm.logout()

        assertThat(vm.uiState.value.isAuthenticated).isFalse()
        assertThat(vm.uiState.value.username).isEmpty()
        verify { loginUseCase.logout() }
    }

    // ── clearError ──

    @Test
    fun `clearError wipes the error field without touching other state`() = runTest {
        every { loginUseCase.login(any(), any()) } returns AuthManager.AuthResult.Failure("Invalid credentials")

        val vm = newVm()
        vm.login("alice", "wrong")
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isNotNull()

        vm.clearError()

        assertThat(vm.uiState.value.error).isNull()
    }

    // ── Emission ordering with Turbine ──

    @Test
    fun `login emits loading then authenticated snapshots in order`() = runTest {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { loginUseCase.login(any(), any()) } returns AuthManager.AuthResult.Success(session)

        val vm = newVm()

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.isAuthenticated).isFalse()

            vm.login("alice", "password123")

            // Drain all state updates; the authenticated snapshot must appear last
            var last = initial
            while (true) {
                val next = awaitItem()
                last = next
                if (next.isAuthenticated) break
            }
            assertThat(last.isAuthenticated).isTrue()
            assertThat(last.isLoading).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

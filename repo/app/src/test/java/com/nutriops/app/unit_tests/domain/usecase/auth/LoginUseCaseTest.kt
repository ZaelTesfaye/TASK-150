package com.nutriops.app.unit_tests.domain.usecase.auth

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.LoginUseCase
import com.nutriops.app.security.AuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class LoginUseCaseTest {

    private lateinit var authManager: AuthManager
    private lateinit var useCase: LoginUseCase

    @Before
    fun setup() {
        authManager = mockk(relaxed = true)
        useCase = LoginUseCase(authManager)
    }

    @Test
    fun `login delegates to AuthManager and propagates success`() {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { authManager.login("alice", "password123") } returns AuthManager.AuthResult.Success(session)

        val result = useCase.login("alice", "password123")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        assertThat((result as AuthManager.AuthResult.Success).session).isEqualTo(session)
        verify { authManager.login("alice", "password123") }
    }

    @Test
    fun `login propagates Failure unchanged`() {
        every { authManager.login(any(), any()) } returns AuthManager.AuthResult.Failure("Invalid credentials")

        val result = useCase.login("alice", "wrong")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Failure::class.java)
        assertThat((result as AuthManager.AuthResult.Failure).message).isEqualTo("Invalid credentials")
    }

    @Test
    fun `login propagates AccountLocked as distinct result type`() {
        every { authManager.login(any(), any()) } returns AuthManager.AuthResult.AccountLocked

        val result = useCase.login("alice", "password123")

        assertThat(result).isSameInstanceAs(AuthManager.AuthResult.AccountLocked)
    }

    @Test
    fun `login passes raw inputs through without modification`() {
        // The use case is a thin delegator — it should not trim/normalize inputs
        // itself. Whitespace must reach AuthManager unchanged so the single source
        // of validation truth lives in one place.
        every { authManager.login(any(), any()) } returns AuthManager.AuthResult.Failure("Invalid credentials")

        useCase.login("  alice  ", " pw ")

        verify { authManager.login("  alice  ", " pw ") }
    }

    @Test
    fun `bootstrap delegates to AuthManager bootstrapAdmin`() {
        val session = AuthManager.AuthSession("u1", "admin", Role.ADMINISTRATOR, LocalDateTime.now())
        every { authManager.bootstrapAdmin("admin", "adminpass1") } returns AuthManager.AuthResult.Success(session)

        val result = useCase.bootstrap("admin", "adminpass1")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        verify { authManager.bootstrapAdmin("admin", "adminpass1") }
    }

    @Test
    fun `register delegates to AuthManager register`() {
        val session = AuthManager.AuthSession("u1", "alice", Role.END_USER, LocalDateTime.now())
        every { authManager.register("alice", "password123") } returns AuthManager.AuthResult.Success(session)

        val result = useCase.register("alice", "password123")

        assertThat(result).isInstanceOf(AuthManager.AuthResult.Success::class.java)
        verify { authManager.register("alice", "password123") }
    }

    @Test
    fun `needsBootstrap reflects AuthManager state`() {
        every { authManager.needsBootstrap() } returns true
        assertThat(useCase.needsBootstrap()).isTrue()

        every { authManager.needsBootstrap() } returns false
        assertThat(useCase.needsBootstrap()).isFalse()
    }

    @Test
    fun `isAuthenticated reflects AuthManager state`() {
        every { authManager.isAuthenticated } returns true
        assertThat(useCase.isAuthenticated()).isTrue()

        every { authManager.isAuthenticated } returns false
        assertThat(useCase.isAuthenticated()).isFalse()
    }

    @Test
    fun `logout delegates to AuthManager`() {
        useCase.logout()
        verify { authManager.logout() }
    }
}

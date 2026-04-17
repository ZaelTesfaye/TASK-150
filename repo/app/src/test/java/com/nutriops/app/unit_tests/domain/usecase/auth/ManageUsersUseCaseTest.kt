package com.nutriops.app.unit_tests.domain.usecase.auth

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.Users
import com.nutriops.app.data.repository.UserRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ManageUsersUseCaseTest {

    private lateinit var userRepository: UserRepository
    private lateinit var useCase: ManageUsersUseCase

    @Before
    fun setup() {
        userRepository = mockk(relaxed = true)
        useCase = ManageUsersUseCase(userRepository)
    }

    // ── createUser ──

    @Test
    fun `createUser succeeds for admin`() = runBlocking {
        coEvery {
            userRepository.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)
        } returns Result.success("u1")

        val result = useCase.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("u1")
    }

    @Test
    fun `createUser denied for agent`() = runBlocking {
        val result = useCase.createUser("alice", "password123", Role.END_USER, "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) {
            userRepository.createUser(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createUser denied for end user`() = runBlocking {
        val result = useCase.createUser("alice", "password123", Role.END_USER, "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `createUser propagates repo failure`() = runBlocking {
        coEvery {
            userRepository.createUser(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalArgumentException("Username already exists"))

        val result = useCase.createUser("alice", "password123", Role.END_USER, "admin1", Role.ADMINISTRATOR)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("already exists")
    }

    // ── getAllUsers ──

    @Test
    fun `getAllUsers succeeds for admin`() = runBlocking {
        val user = mockk<Users>(relaxed = true)
        coEvery { userRepository.getAllUsers() } returns listOf(user)

        val result = useCase.getAllUsers(Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(1)
    }

    @Test
    fun `getAllUsers denied for agent`() = runBlocking {
        val result = useCase.getAllUsers(Role.AGENT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { userRepository.getAllUsers() }
    }

    @Test
    fun `getAllUsers denied for end user`() = runBlocking {
        val result = useCase.getAllUsers(Role.END_USER)
        assertThat(result.isFailure).isTrue()
    }

    // ── deactivateUser ──

    @Test
    fun `deactivateUser succeeds for admin`() = runBlocking {
        coEvery {
            userRepository.deactivateUser("u1", "admin1", Role.ADMINISTRATOR)
        } returns Result.success(Unit)

        val result = useCase.deactivateUser("u1", "admin1", Role.ADMINISTRATOR)

        assertThat(result.isSuccess).isTrue()
        coVerify { userRepository.deactivateUser("u1", "admin1", Role.ADMINISTRATOR) }
    }

    @Test
    fun `deactivateUser denied for agent`() = runBlocking {
        val result = useCase.deactivateUser("u1", "agent1", Role.AGENT)
        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { userRepository.deactivateUser(any(), any(), any()) }
    }

    @Test
    fun `deactivateUser denied for end user`() = runBlocking {
        val result = useCase.deactivateUser("u1", "user1", Role.END_USER)
        assertThat(result.isFailure).isTrue()
    }
}

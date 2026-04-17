package com.nutriops.app.unit_tests.domain.usecase.mealplan

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.MealPlans
import com.nutriops.app.data.local.SwapMappings
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class SwapMealUseCaseTest {

    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var useCase: SwapMealUseCase

    @Before
    fun setup() {
        mealPlanRepository = mockk(relaxed = true)
        useCase = SwapMealUseCase(mealPlanRepository)
    }

    private fun swap(
        id: String = "swap1",
        originalMealId: String = "meal1",
        withinTolerance: Boolean = true,
        caloriesDiffPercent: Double = 3.0,
        proteinDiffGrams: Double = 1.0
    ): SwapMappings = SwapMappings(
        id = id,
        originalMealId = originalMealId,
        swapMealName = "Alt Bowl",
        swapDescription = "alt",
        swapCalories = 620,
        swapProteinGrams = 31.0,
        swapCarbGrams = 72.0,
        swapFatGrams = 18.0,
        caloriesDiffPercent = caloriesDiffPercent,
        proteinDiffGrams = proteinDiffGrams,
        isWithinTolerance = if (withinTolerance) 1L else 0L,
        swapReasons = "[\"r1\",\"r2\"]",
        rankScore = 95.0,
        createdAt = "2026-04-10T12:00:00"
    )

    private fun ownerPlan(userId: String = "user1"): MealPlans = mockk(relaxed = true) {
        io.mockk.every { this@mockk.userId } returns userId
    }

    // ── executeSwap ──

    @Test
    fun `executeSwap delegates to repository for the owner and returns the new meal id`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("user1")
        coEvery { mealPlanRepository.getSwapsForMeal("meal1") } returns listOf(swap("swap1"))
        coEvery { mealPlanRepository.performSwap("meal1", "swap1", "user1", Role.END_USER) } returns
            Result.success("newMeal1")

        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap1",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("newMeal1")
        coVerify { mealPlanRepository.performSwap("meal1", "swap1", "user1", Role.END_USER) }
    }

    @Test
    fun `executeSwap denies end user trying to swap another users meal`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("userB")

        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap1",
            actorId = "userA",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { mealPlanRepository.performSwap(any(), any(), any(), any()) }
    }

    @Test
    fun `executeSwap rejects unknown meal id`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("bogus") } returns null

        val result = useCase.executeSwap(
            originalMealId = "bogus",
            swapMappingId = "swap1",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { mealPlanRepository.performSwap(any(), any(), any(), any()) }
    }

    @Test
    fun `executeSwap denies swap mapping that does not belong to the target meal (IDOR)`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("user1")
        // Mapping "swap99" exists but is not associated with meal1
        coEvery { mealPlanRepository.getSwapsForMeal("meal1") } returns listOf(swap("swap1"))

        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap99",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { mealPlanRepository.performSwap(any(), any(), any(), any()) }
    }

    @Test
    fun `executeSwap propagates repository failure when swap is outside tolerance`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("user1")
        coEvery { mealPlanRepository.getSwapsForMeal("meal1") } returns listOf(swap("swap1", withinTolerance = false))
        coEvery { mealPlanRepository.performSwap("meal1", "swap1", "user1", Role.END_USER) } returns
            Result.failure(IllegalArgumentException("Swap is outside tolerance bounds"))

        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap1",
            actorId = "user1",
            actorRole = Role.END_USER
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("tolerance")
    }

    @Test
    fun `administrator can swap any users meal`() = runBlocking {
        coEvery { mealPlanRepository.getSwapsForMeal("meal1") } returns listOf(swap("swap1"))
        coEvery { mealPlanRepository.performSwap("meal1", "swap1", "admin1", Role.ADMINISTRATOR) } returns
            Result.success("newMeal1")

        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap1",
            actorId = "admin1",
            actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
    }

    // ── getAvailableSwaps ──

    @Test
    fun `getAvailableSwaps returns swap options for the owner`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("user1")
        coEvery { mealPlanRepository.getSwapsForMeal("meal1") } returns listOf(
            swap("s1"), swap("s2", withinTolerance = false)
        )

        val result = useCase.getAvailableSwaps("meal1", "user1", Role.END_USER)

        assertThat(result.isSuccess).isTrue()
        val options = result.getOrNull()!!
        assertThat(options).hasSize(2)
        assertThat(options.map { it.swapId }).containsExactly("s1", "s2")
    }

    @Test
    fun `getAvailableSwaps denies end user viewing another users swaps`() = runBlocking {
        coEvery { mealPlanRepository.getMealPlanForMeal("meal1") } returns ownerPlan("userB")

        val result = useCase.getAvailableSwaps("meal1", "userA", Role.END_USER)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `agent role is not permitted to swap meals`() = runBlocking {
        val result = useCase.executeSwap(
            originalMealId = "meal1",
            swapMappingId = "swap1",
            actorId = "agent1",
            actorRole = Role.AGENT
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }
}

package com.nutriops.app.domain.usecase.profile

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.AgeRange
import com.nutriops.app.domain.model.HealthGoal
import io.mockk.mockk
import org.junit.Test

class ManageProfileUseCaseTest {

    private val useCase = ManageProfileUseCase(
        profileRepository = mockk(relaxed = true)
    )

    @Test
    fun `calculateMacros for lose 1 lb per week age 26-35`() {
        val macros = useCase.calculateMacros(HealthGoal.LOSE_1_LB_WEEK, AgeRange.AGE_26_35)
        // Base 2100 - 500 = 1600
        assertThat(macros.calories).isEqualTo(1600)
        assertThat(macros.proteinGrams).isEqualTo(120) // 30% of 1600 / 4
        assertThat(macros.carbGrams).isEqualTo(160)    // 40% of 1600 / 4
        assertThat(macros.fatGrams).isEqualTo(53)      // 30% of 1600 / 9
    }

    @Test
    fun `calculateMacros for maintain weight age 46-55`() {
        val macros = useCase.calculateMacros(HealthGoal.MAINTAIN, AgeRange.AGE_46_55)
        assertThat(macros.calories).isEqualTo(1900)
    }

    @Test
    fun `calculateMacros for gain 1 lb per week age 18-25`() {
        val macros = useCase.calculateMacros(HealthGoal.GAIN_1_LB_WEEK, AgeRange.AGE_18_25)
        // Base 2200 + 500 = 2700
        assertThat(macros.calories).isEqualTo(2700)
    }

    @Test
    fun `calories never go below 1200`() {
        val macros = useCase.calculateMacros(HealthGoal.LOSE_2_LB_WEEK, AgeRange.AGE_65_PLUS)
        // Base 1700 - 1000 = 700, but clamped to 1200
        assertThat(macros.calories).isAtLeast(1200)
    }

    @Test
    fun `macros add up to total calories`() {
        val macros = useCase.calculateMacros(HealthGoal.MAINTAIN, AgeRange.AGE_26_35)
        val totalFromMacros = macros.proteinGrams * 4 + macros.carbGrams * 4 + macros.fatGrams * 9
        // Should be approximately equal to total calories (within rounding)
        assertThat(totalFromMacros).isWithin(50).of(macros.calories)
    }

    @Test
    fun `all age ranges produce valid macros`() {
        for (age in AgeRange.entries) {
            for (goal in HealthGoal.entries) {
                val macros = useCase.calculateMacros(goal, age)
                assertThat(macros.calories).isAtLeast(1200)
                assertThat(macros.proteinGrams).isGreaterThan(0)
                assertThat(macros.carbGrams).isGreaterThan(0)
                assertThat(macros.fatGrams).isGreaterThan(0)
            }
        }
    }
}
